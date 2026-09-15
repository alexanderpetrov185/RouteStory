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
            accumulatedMs = 0L
            startTime = now
            segmentStartMs = now
            val dateText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
            trackName = getString(R.string.track_name_prefix) + dateText
        } else {
            segmentStartMs = now
        }
        recording = true
        requestLocationUpdates()
        stateHolder.update { it.copy(state = TrackingState.RECORDING) }
        updateNotification()
    }

    private fun pauseRecording() {
        if (!recording) return
        recording = false
        safeRemoveLocationUpdates()
        accumulatedMs += System.currentTimeMillis() - segmentStartMs
        stateHolder.update { it.copy(state = TrackingState.PAUSED, durationMs = accumulatedMs) }
        updateNotification()
    }

    private fun stopRecording() {
        if (recording) {
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            recording = false
        }
        safeRemoveLocationUpdates()
        val snapshot = points.toList()
        val name = trackName
        val start = startTime
        val end = System.currentTimeMillis()
        val distance = totalDistanceM
        val duration = accumulatedMs
        lifecycleScope.launch {
            if (snapshot.size >= 2) {
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
            }
            points.clear()
            stateHolder.reset()
            stateHolder.notifyTrackSaved()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@TrackRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleLocation(location: Location) {
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
        if (previous != null) totalDistanceM += GeoUtils.distanceMeters(previous, candidate)
        points.add(candidate)
        val now = System.currentTimeMillis()
        stateHolder.update {
            it.copy(
                points = points.toList(),
                distanceMeters = totalDistanceM,
                durationMs = accumulatedMs + (now - segmentStartMs),
                speedMps = candidate.speed
            )
        }
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
    }
}