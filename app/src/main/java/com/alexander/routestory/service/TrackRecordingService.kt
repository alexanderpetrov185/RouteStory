package com.alexander.routestory.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alexander.routestory.MainActivity
import com.alexander.routestory.R
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.domain.TrackRepository
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.utils.GeoUtils
import com.alexander.routestory.utils.LocationFilter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TrackRecordingService : LifecycleService() {

    @Inject lateinit var stateHolder: TrackingStateHolder
    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var locationClient: FusedLocationProviderClient

    private val points = mutableListOf<TrackPoint>()
    private var trackName = ""
    private var startTime = 0L
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private var totalDistanceM = 0f
    private var lastSpeedMps = 0f
    private var lastPointTimeMs = 0L
    private var tickJob: Job? = null

    @Volatile
    private var recording = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        startForeground()
        val currentState = stateHolder.uiState.value.state
        if (currentState == TrackingState.RECORDING) return
        val now = System.currentTimeMillis()
        if (currentState == TrackingState.IDLE) {
            points.clear()
            totalDistanceM = 0f
            lastSpeedMps = 0f
            accumulatedMs = 0L
            startTime = now
            segmentStartMs = now
            val dateText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
            trackName = getString(R.string.track_name_prefix) + dateText
        } else {
            segmentStartMs = now
        }
        recording = true
        lastPointTimeMs = now
        startTicker()
        requestLocationUpdates()
        stateHolder.update { it.copy(state = TrackingState.RECORDING, speedMps = 0f) }
        updateNotification()
    }

    private fun pauseRecording() {
        if (!recording) return
        recording = false
        stopTicker()
        safeRemoveLocationUpdates()
        accumulatedMs += System.currentTimeMillis() - segmentStartMs
        lastSpeedMps = 0f
        stateHolder.update {
            it.copy(state = TrackingState.PAUSED, durationMs = accumulatedMs, speedMps = 0f)
        }
        updateNotification()
    }

    private fun stopRecording() {
        if (recording) {
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            recording = false
        }
        stopTicker()
        safeRemoveLocationUpdates()
        val snapshot = points.toList()
        val name = trackName
        val start = startTime
        val end = System.currentTimeMillis()
        val distance = totalDistanceM
        val duration = accumulatedMs
        points.clear()
        totalDistanceM = 0f
        lastSpeedMps = 0f
        stateHolder.reset()
        lifecycleScope.launch {
            // The service is torn down right after saving, so the write must not be cancellable.
            val trackId = withContext(NonCancellable + Dispatchers.IO) {
                if (snapshot.isEmpty()) {
                    null
                } else {
                    runCatching {
                        repository.saveTrack(
                            Track(
                                name = name,
                                startTime = start,
                                endTime = end,
                                distance = distance,
                                duration = duration,
                                points = snapshot
                            )
                        )
                    }.getOrNull()
                }
            }
            if (trackId != null) stateHolder.notifySaved(trackId) else stateHolder.notifyDiscarded()
            ServiceCompat.stopForeground(this@TrackRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleLocation(location: Location) {
        // The live "me" dot must move on every fix, even when the point is too noisy
        // or too close to be stored in the recorded track.
        stateHolder.update { it.copy(lastFix = location.latitude to location.longitude) }
        val candidate = TrackPoint(
            lat = location.latitude,
            lon = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            accuracy = location.accuracy,
            timestamp = location.time
        )
        val previous = points.lastOrNull()
        if (!LocationFilter.shouldAccept(previous, candidate)) return
        if (LocationFilter.isSignificantMove(previous, candidate)) {
            previous?.let { totalDistanceM += GeoUtils.distanceMeters(it, candidate) }
        }
        points.add(candidate)
        lastPointTimeMs = System.currentTimeMillis()
        lastSpeedMps = if (location.hasSpeed() && location.speed > 0f) {
            location.speed
        } else {
            computedSpeed()
        }
        pushLiveState()
    }

    /** Speed derived from the last two recorded points, used when the fix reports no speed. */
    private fun computedSpeed(): Float {
        if (points.size < 2) return 0f
        val previous = points[points.size - 2]
        val last = points.last()
        val dtSec = (last.timestamp - previous.timestamp) / 1000f
        if (dtSec <= 0f) return 0f
        return GeoUtils.distanceMeters(previous, last) / dtSec
    }

    /** Publishes the live telemetry; used both on new fixes and by the ticker. */
    private fun pushLiveState() {
        val now = System.currentTimeMillis()
        val liveDurationMs = accumulatedMs + if (recording) now - segmentStartMs else 0L
        val speedOutdated = recording && now - lastPointTimeMs > SPEED_STALE_MS
        stateHolder.update {
            it.copy(
                points = points.toList(),
                distanceMeters = totalDistanceM,
                durationMs = liveDurationMs,
                speedMps = if (speedOutdated) 0f else lastSpeedMps
            )
        }
    }

    /** Keeps the timer running even while no GPS fixes are accepted. */
    private fun startTicker() {
        stopTicker()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (recording) pushLiveState()
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Location updates require a granted permission; a revoked one must not crash the service. */
    private fun safeRemoveLocationUpdates() {
        try {
            locationClient.removeLocationUpdates(locationCallback)
        } catch (_: SecurityException) {
            // Permission revoked, nothing to remove
        }
    }

    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked mid-session: pause gracefully instead of crashing
            recording = false
            stateHolder.update { it.copy(state = TrackingState.PAUSED) }
            updateNotification()
        }
    }

    private fun startForeground() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val (pauseAction, pauseLabel) = if (recording) {
            ACTION_PAUSE to getString(R.string.action_pause)
        } else {
            ACTION_START to getString(R.string.action_resume)
        }
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TrackRecordingService::class.java).setAction(pauseAction),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TrackRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = getString(if (recording) R.string.notif_recording else R.string.notif_paused)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.alexander.routestory.action.START"
        const val ACTION_PAUSE = "com.alexander.routestory.action.PAUSE"
        const val ACTION_STOP = "com.alexander.routestory.action.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 2000L
        private const val TICK_MS = 500L
        /** After this long without an accepted fix the displayed speed drops to zero. */
        private const val SPEED_STALE_MS = 10_000L
    }
}