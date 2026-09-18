package com.alexander.routestory.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alexander.routestory.MainActivity
import com.alexander.routestory.R
import com.alexander.routestory.data.ProRepository
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
import kotlinx.coroutines.flow.first
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
    @Inject lateinit var draftStore: TrackDraftStore
    @Inject lateinit var proRepository: ProRepository
    @Inject lateinit var locationClient: FusedLocationProviderClient

    private var saving = false
    private var draftJob: Job? = null
    private val points = mutableListOf<TrackPoint>()
    private var trackName = ""
    private var startTime = 0L
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private var totalDistanceM = 0f
    private var lastSpeedMps = 0f
    private var lastPointTimeMs = 0L
    private var tickJob: Job? = null
    /** Throttled periodic draft save so a kill loses at most a few seconds. */
    private var lastDraftSaveMs = 0L

    /**
     * Position known right before Start (usually the lastFix shown on the map). The first
     * recorded fix is compared against it to drop a startup teleport.
     */
    private var startAnchor: Pair<Double, Double>? = null

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
            null -> restoreDraft(resume = true)
            ACTION_FLUSH_DRAFT -> flushDraft()
            ACTION_DISCARD_DRAFT -> {
                if (stateHolder.uiState.value.state == TrackingState.IDLE) {
                    draftStore.load()?.photos?.forEach { java.io.File(it.uri).delete() }
                    draftStore.clear()
                    stateHolder.setDraftAvailable(false)
                    lifecycleScope.launch {
                        proRepository.setDiscardConfirmed(true)
                    }
                    stopSelf()
                }
            }
            ACTION_RESTORE_DRAFT -> restoreDraft(resume = true)
            ACTION_CHECK_DRAFT -> {
                checkDraft()
                // Nothing to recover: do not leave a sticky background service behind.
                if (!stateHolder.uiState.value.draftAvailable) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away kill: flush the draft synchronously so the ride survives, and leave a
        // plain notification behind explaining how to continue (the foreground-service
        // notification disappears together with the service).
        val wasActive = stateHolder.uiState.value.state != TrackingState.IDLE
        flushDraft()
        if (wasActive && draftStore.hasDraft()) notifyDraftInterrupted()
        super.onTaskRemoved(rootIntent)
    }

    /** Survives the service: stays in the shade until the user returns to the app. */
    private fun notifyDraftInterrupted() {
        runCatching {
            val intent = Intent(this, com.alexander.routestory.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.draft_notification_title))
                .setContentText(getString(R.string.draft_notification_text))
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.draft_notification_text))
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(this).notify(DRAFT_NOTIFICATION_ID, notification)
        }.onFailure { android.util.Log.w("TrackService", "draft notification failed", it) }
    }

    override fun onDestroy() {
        flushDraft()
        super.onDestroy()
    }

    /** Publishes whether a recoverable draft exists (called when the app opens). */
    private fun checkDraft() {
        if (stateHolder.uiState.value.state != TrackingState.IDLE) return
        lifecycleScope.launch {
            val skip = proRepository.discardConfirmed.first()
            if (skip) {
                stateHolder.setDraftAvailable(false)
                cancelDraftNotification()
                return@launch
            }
            stateHolder.setDraftAvailable(draftStore.hasDraft())
            if (stateHolder.uiState.value.draftAvailable) notifyDraftInterrupted()
        }
    }

    /** Restores a killed session and immediately keeps recording, so one tap resumes. */
    private fun restoreDraft(resume: Boolean) {
        val draft = draftStore.load() ?: run {
            stateHolder.setDraftAvailable(false)
            return
        }
        if (stateHolder.uiState.value.state != TrackingState.IDLE) return
        points.clear()
        points.addAll(draft.points)
        startTime = draft.startTimeMs
        accumulatedMs = draft.accumulatedMs
        totalDistanceM = draft.totalDistanceM
        trackName = draft.trackName
        segmentStartMs = System.currentTimeMillis()
        startAnchor = draft.points.lastOrNull()?.let { it.lat to it.lon }
        stateHolder.update {
            it.copy(
                state = TrackingState.PAUSED,
                sessionId = draft.sessionId,
                photos = draft.photos,
                capturePending = false,
                points = draft.points.toList(),
                distanceMeters = draft.totalDistanceM,
                durationMs = draft.accumulatedMs,
                speedMps = 0f,
                draftAvailable = false
            )
        }
        startForeground()
        updateNotification()
        if (resume) {
            // One-tap continuation: the user picked Resume, so go straight back to
            // RECORDING instead of leaving the restored ride paused.
            recording = true
            lastPointTimeMs = System.currentTimeMillis()
            startTicker()
            requestLocationUpdates()
            stateHolder.update { it.copy(state = TrackingState.RECORDING, speedMps = 0f) }
            updateNotification()
        }
        androidx.core.app.NotificationManagerCompat.from(this).cancel(DRAFT_NOTIFICATION_ID)
        android.util.Log.i(
            "TrackService",
            "draft restored: points=${draft.points.size} photos=${draft.photos.size} resume=$resume"
        )
    }
    private fun draftSnapshot(): TrackDraft? {
        val st = stateHolder.uiState.value
        if (st.state == TrackingState.IDLE || st.sessionId.isEmpty()) return null
        val now = System.currentTimeMillis()
        return TrackDraft(st.sessionId, trackName, startTime,
            accumulatedMs + if (recording) now - segmentStartMs else 0L,
            totalDistanceM, st.state, points.toList(), st.photos.toList(), now)
    }

    private fun flushDraft() {
        draftSnapshot()?.let(draftStore::save)
    }

    private fun startRecording() {
        startForeground()
        val currentState = stateHolder.uiState.value.state
        if (currentState == TrackingState.RECORDING) return
        val now = System.currentTimeMillis()
        if (currentState == TrackingState.IDLE) {
            points.clear()
            stateHolder.update { it.copy(sessionId = java.util.UUID.randomUUID().toString(), photos = emptyList(), capturePending = false) }
            totalDistanceM = 0f
            lastSpeedMps = 0f
            accumulatedMs = 0L
            startTime = now
            segmentStartMs = now
            // Anchor for the startup teleport guard: whatever the map showed before Start.
            startAnchor = stateHolder.uiState.value.lastFix
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
        // Persist immediately: while paused the ticker is stopped, so a kill would
        // otherwise fall back to the previous periodic snapshot.
        saveDraftAsync(force = true)
        updateNotification()
    }

    private fun stopRecording() {
        if (stateHolder.uiState.value.capturePending) return
        val photos = stateHolder.uiState.value.photos.toList()
        if (recording) {
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            recording = false
        }
        stopTicker()
        safeRemoveLocationUpdates()
        val snapshot = points.toList()
        val end = System.currentTimeMillis()
        val distance = totalDistanceM
        val duration = accumulatedMs
        // Light-hearted auto name from distance/duration; the user can rename in the summary.
        val name = com.alexander.routestory.utils.TripNames.generate(distance, duration, end)
        val start = startTime
        points.clear()
        totalDistanceM = 0f
        lastSpeedMps = 0f
        startAnchor = null
        stateHolder.reset()
        lifecycleScope.launch {
            // The service is torn down right after saving, so the write must not be cancellable.
            var saveFailed = false
            // A ride is worth keeping whenever it has either two points (a real line) or
            // at least a meter of measured distance. "Not enough data" is reserved for
            // truly empty recordings, not for short ones.
            val hasUsableData = snapshot.size >= 2 || distance >= 1f
            val trackId = withContext(NonCancellable + Dispatchers.IO) {
                if (!hasUsableData) {
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
                                points = snapshot,
                                photos = photos
                            )
                        )
                    }.onFailure { saveFailed = true }.getOrNull()
                }
            }
            if (trackId == null && !saveFailed) photos.forEach { java.io.File(it.uri).delete() }
            if (trackId != null) stateHolder.notifySaved(trackId) else stateHolder.notifyDiscarded()
            // A failed write keeps the draft: the ride can still be recovered instead of
            // being silently lost together with the process.
            if (!saveFailed) {
                draftStore.clear()
                stateHolder.setDraftAvailable(false)
            } else {
                flushDraft()
            }
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
        // Startup teleport guard: the very first fix after Start can be far away from the
        // pre-start lastFix (cold GPS start / network-based fix). Recording it would draw a
        // fake line across the map, so the anchor is simply moved to the new position and
        // the fix is not stored.
        if (previous == null) {
            val anchor = startAnchor
            if (anchor != null) {
                val jump = GeoUtils.distanceMeters(anchor.first, anchor.second, candidate.lat, candidate.lon)
                if (jump > MAX_START_JUMP_M) {
                    Log.i("TrackService", "start teleport ignored: ${jump.toInt()} m")
                    startAnchor = candidate.lat to candidate.lon
                    pushLiveState()
                    return
                }
            }
        }
        if (!LocationFilter.shouldAccept(previous, candidate)) return
        if (LocationFilter.isSignificantMove(previous, candidate)) {
            previous?.let { totalDistanceM += GeoUtils.distanceMeters(it, candidate) }
        }
        points.add(candidate)
        // Every N-th accepted point also refreshes the draft: the ticker alone can miss
        // a burst of fast fixes right before the process is killed.
        if (points.size % DRAFT_POINT_STEP == 0) saveDraftAsync()
        lastPointTimeMs = System.currentTimeMillis()
        // Draft every 10th accepted point as well, so fast movement is covered too.
        if (points.size % 10 == 0) saveDraftAsync()
        // GPS drift filter: when the user is essentially stationary (last point
        // is less than STATIONARY_DISTANCE_M from the new fix), the GPS "speed"
        // field is unreliable noise — freeze the speed display at 0 instead of
        // showing spurious 1-5 m/s values.
        val distanceFromLast = previous?.let { GeoUtils.distanceMeters(it, candidate) } ?: 0f
        lastSpeedMps = if (distanceFromLast < STATIONARY_DISTANCE_M) {
            0f
        } else if (location.hasSpeed() && location.speed > 0f) {
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
            var tick = 0
            while (isActive) {
                delay(TICK_MS)
                tick++
                if (recording) {
                    pushLiveState()
                    // Periodic draft save (~10s): a kill loses at most a few seconds.
                    if (tick % 20 == 0) saveDraftAsync()
                }
            }
        }
    }

    /** Throttled async draft write (at most once per 5s unless [force]). */
    private fun saveDraftAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDraftSaveMs < 5_000L) return
        lastDraftSaveMs = now
        val snapshot = draftSnapshot() ?: return
        lifecycleScope.launch(Dispatchers.IO) { draftStore.save(snapshot) }
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

    /** Cancels the draft-interrupted notification. */
    private fun cancelDraftNotification() {
        NotificationManagerCompat.from(this).cancel(DRAFT_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_START = "com.alexander.routestory.action.START"
        const val ACTION_PAUSE = "com.alexander.routestory.action.PAUSE"
        const val ACTION_STOP = "com.alexander.routestory.action.STOP"
        const val ACTION_DISCARD_DRAFT = "com.alexander.routestory.action.DISCARD_DRAFT"
        const val ACTION_RESTORE_DRAFT = "com.alexander.routestory.action.RESTORE_DRAFT"
        const val ACTION_FLUSH_DRAFT = "com.alexander.routestory.action.FLUSH_DRAFT"
        const val ACTION_CHECK_DRAFT = "com.alexander.routestory.action.CHECK_DRAFT"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1

        /** Plain (non-foreground) notification telling the user the ride was interrupted. */
        private const val DRAFT_NOTIFICATION_ID = 1001
        /** First fix farther than this from the pre-start position is treated as a teleport. */
        private const val MAX_START_JUMP_M = 150f
        private const val UPDATE_INTERVAL_MS = 2000L
        private const val TICK_MS = 500L
        /** After this long without an accepted fix the displayed speed drops to zero. */
        private const val SPEED_STALE_MS = 10_000L
        /** Accepted points between two draft writes. */
        private const val DRAFT_POINT_STEP = 10
        /** Distance below which a new GPS fix is considered "standing still" for speed display. */
        private const val STATIONARY_DISTANCE_M = 5f
    }
}
