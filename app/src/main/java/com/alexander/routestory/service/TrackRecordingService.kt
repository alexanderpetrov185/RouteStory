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
    @Inject lateinit var pendingNavigation: PendingNavigationStore
    @Inject lateinit var proRepository: ProRepository
    @Inject lateinit var locationClient: FusedLocationProviderClient

    private var saving = false
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
            // The system may have recreated this instance while the ride kept living in the
            // shared state (notification action, task brought back). Without hydrating, the
            // private fields are zero and a Stop would store an empty, epoch-dated ride.
            // pauseRecording()/stopRecording() hydrate themselves; a null intent is a
            // START_STICKY restart and goes through restoreDraft() below.
            ACTION_START -> {
                hydrateSessionFromState()
                startRecording()
            }
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
            ACTION_CHECK_DRAFT -> checkDraft()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task swiped away: flush the draft synchronously so a later process death cannot lose
        // the ride. The foreground service (and its single ongoing notification) keeps running
        // and recording; "interrupted" is posted only when the app comes back and finds a draft
        // without a live session.
        flushDraft()
        super.onTaskRemoved(rootIntent)
    }

    /** Survives the service: stays in the shade until the user returns to the app. */
    private fun notifyDraftInterrupted() {
        runCatching {
            val intent = Intent(this, com.alexander.routestory.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // Sprint 27: opens the recording screen, never a bare Home.
                .putExtra(MainActivity.EXTRA_OPEN_TRACKING, true)
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

    /**
     * Publishes whether a recoverable draft exists (called when the app opens). A live ride is
     * never stopped here: killing the service mid-recording is exactly what used to zero the
     * next saved track. The "interrupted" notification appears only when a draft exists and no
     * ride is running, i.e. after a real process break.
     */
    private fun checkDraft() {
        if (stateHolder.uiState.value.state != TrackingState.IDLE) {
            // The ride is alive: keep a single ongoing notification, no "interrupted" duplicate.
            cancelDraftNotification()
            return
        }
        lifecycleScope.launch {
            val skip = proRepository.discardConfirmed.first()
            val hasDraft = !skip && draftStore.hasDraft()
            stateHolder.setDraftAvailable(hasDraft)
            if (hasDraft) notifyDraftInterrupted() else cancelDraftNotification()
            // Nothing to recover: do not leave a sticky background service behind.
            if (!hasDraft) stopSelf()
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
        startTime = draft.startTimeMs.takeIf { it > 0L }
            ?: draft.points.firstOrNull()?.timestamp
            ?: 0L
        accumulatedMs = draft.accumulatedMs
        totalDistanceM = draft.totalDistanceM
        trackName = draft.trackName.ifEmpty { stateHolder.uiState.value.trackName }
        segmentStartMs = System.currentTimeMillis()
        startAnchor = draft.points.lastOrNull()?.let { it.lat to it.lon }
        // A ride that the user had paused stays paused after recovery ("Продолжить" is an explicit
        // tap); a ride that was rolling when it was cut keeps recording, as before.
        val restoreState = if (draft.status == TrackingState.PAUSED) {
            TrackingState.PAUSED
        } else {
            TrackingState.RECORDING
        }
        stateHolder.update {
            it.copy(
                state = restoreState,
                sessionId = draft.sessionId,
                startTimeMs = startTime,
                trackName = trackName,
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
        if (resume && restoreState == TrackingState.RECORDING) {
            // One-tap continuation: the user picked Resume, so go straight back to
            // RECORDING instead of leaving the restored ride paused.
            recording = true
            lastPointTimeMs = System.currentTimeMillis()
            startTicker()
            requestLocationUpdates()
            updateNotification()
        } else if (resume) {
            android.util.Log.i("TrackService", "draft restored paused: awaiting an explicit resume")
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
        // The state is the authority for a recreated instance: fall back to it (and to the first
        // recorded fix) instead of writing zeros into the draft.
        val livePoints = points.ifEmpty { st.points }
        val startMs = startTime.takeIf { it > 0L }
            ?: st.startTimeMs.takeIf { it > 0L }
            ?: livePoints.firstOrNull()?.timestamp
            ?: now
        return TrackDraft(
            st.sessionId,
            trackName.ifEmpty { st.trackName },
            startMs,
            accumulatedMs + if (recording) now - segmentStartMs else 0L,
            totalDistanceM.takeIf { it > 0f } ?: st.distanceMeters,
            st.state,
            livePoints.toList(),
            st.photos.toList(),
            now
        )
    }

    private fun flushDraft() {
        draftSnapshot()?.let(draftStore::save)
    }

    /**
     * Rebuilds this instance's session fields from the shared state (and from the draft when the
     * state carries no start time yet). The ride belongs to the state holder, not to the service
     * instance: Android may destroy and recreate the service at any moment, and the private
     * fields would come back as zeros - which is what produced empty, 01.01.1970 rides.
     */
    private fun hydrateSessionFromState() {
        if (startTime > 0L && points.isNotEmpty()) return
        val st = stateHolder.uiState.value
        if (st.state == TrackingState.IDLE) {
            // No live session in memory: only an on-disk draft can still describe the ride.
            adoptDraftIfNeeded()
            return
        }
        val draft = if (st.sessionId.isNotEmpty() && (st.startTimeMs <= 0L || st.points.isEmpty())) {
            // Trusted only when it belongs to this very session: an abandoned draft from an
            // earlier ride must never leak into the current one.
            draftStore.load()?.takeIf { it.sessionId == st.sessionId }
        } else {
            null
        }
        if (startTime <= 0L) {
            startTime = st.startTimeMs.takeIf { it > 0L } ?: draft?.startTimeMs ?: 0L
        }
        if (trackName.isEmpty()) trackName = st.trackName.ifEmpty { draft?.trackName.orEmpty() }
        if (points.isEmpty()) points.addAll(st.points.ifEmpty { draft?.points.orEmpty() })
        if (totalDistanceM <= 0f) {
            totalDistanceM = st.distanceMeters.takeIf { it > 0f } ?: draft?.totalDistanceM ?: 0f
        }
        if (accumulatedMs <= 0L) {
            accumulatedMs = st.durationMs.takeIf { it > 0L } ?: draft?.accumulatedMs ?: 0L
        }
        startAnchor = points.lastOrNull()?.let { it.lat to it.lon }
        segmentStartMs = System.currentTimeMillis()
        lastPointTimeMs = segmentStartMs
        if (st.state == TrackingState.RECORDING) recording = true
        android.util.Log.i(
            "TrackService",
            "session hydrated: state=${st.state} points=${points.size} start=$startTime " +
                "distance=${totalDistanceM.toInt()}m"
        )
    }

    /** Adopts an on-disk draft when this instance has no session at all (Stop from the shade). */
    private fun adoptDraftIfNeeded() {
        if (points.isNotEmpty() || startTime > 0L) return
        val draft = draftStore.load() ?: return
        val session = stateHolder.uiState.value.sessionId
        if (session.isNotEmpty() && draft.sessionId != session) return
        points.addAll(draft.points)
        startTime = draft.startTimeMs
        accumulatedMs = draft.accumulatedMs
        totalDistanceM = draft.totalDistanceM
        trackName = draft.trackName
        if (stateHolder.uiState.value.photos.isEmpty() && draft.photos.isNotEmpty()) {
            stateHolder.update { it.copy(photos = draft.photos) }
        }
        android.util.Log.i("TrackService", "draft adopted: points=${points.size} start=$startTime")
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
            // Anchor for the startup teleport guard: whatever the map showed before Start.
            startAnchor = stateHolder.uiState.value.lastFix
            val dateText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
            trackName = getString(R.string.track_name_prefix) + dateText
            // The session - including its start time and name - lives in the shared state, so a
            // recreated service instance can still save a correctly dated ride.
            stateHolder.update {
                it.copy(
                    sessionId = java.util.UUID.randomUUID().toString(),
                    startTimeMs = now,
                    trackName = trackName,
                    photos = emptyList(),
                    capturePending = false
                )
            }
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
        // A Pause from the notification shade can land on a freshly created instance: pull the
        // session back before touching the accumulated time.
        hydrateSessionFromState()
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
        // A second Stop (double tap, or the shade action right after the in-app button) must
        // not discard the ride that is already being written.
        if (saving) return
        saving = true
        // The ride may live in the shared state (or in the draft after a process restart): pull
        // it back before deciding what to store.
        hydrateSessionFromState()
        val photos = stateHolder.uiState.value.photos.toList()
        // Captured before reset(): a session exists from the moment Start ran, even when the GPS
        // never produced an acceptable fix, and also when only a draft survived.
        val hadSession = startTime > 0L || stateHolder.uiState.value.sessionId.isNotEmpty() ||
            points.isNotEmpty() || photos.isNotEmpty() || draftStore.hasDraft()
        if (recording) {
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            recording = false
        }
        stopTicker()
        safeRemoveLocationUpdates()
        val snapshot = points.toList()
        val end = System.currentTimeMillis()
        val distance = totalDistanceM
        // Never store an epoch date: fall back to the first recorded fix, then to "now".
        val start = startTime.takeIf { it > 0L }
            ?: snapshot.firstOrNull()?.timestamp
            ?: end
        val duration = accumulatedMs.takeIf { it > 0L } ?: (end - start).coerceAtLeast(0L)
        // Light-hearted auto name from distance/duration; the user can rename in the summary.
        val name = com.alexander.routestory.utils.TripNames.generate(distance, duration, end)
        points.clear()
        totalDistanceM = 0f
        lastSpeedMps = 0f
        startAnchor = null
        stateHolder.reset()
        lifecycleScope.launch {
            // The service is torn down right after saving, so the write must not be cancellable.
            var saveFailed = false
            // Sprint 27: a Stop of a started ride is always stored, even with one point, zero
            // distance or zero duration. "Недостаточно данных" is reserved for a Stop without
            // any session at all.
            val trackId = withContext(NonCancellable + Dispatchers.IO) {
                if (!hadSession) {
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
            if (trackId != null) {
                // A cold start after a Stop from the shade must open this ride, not Home.
                pendingNavigation.setSummaryTrack(trackId)
                stateHolder.notifySaved(trackId)
            } else {
                stateHolder.notifyDiscarded()
            }
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
        // A live recording is the single source of truth: drop the "interrupted" copy so the
        // shade never shows both notifications at once.
        cancelDraftNotification()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun updateNotification() {
        cancelDraftNotification()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        // Sprint 27: tapping the ongoing notification must land on the recording screen.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_TRACKING, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            // One persistent, non-swipeable notification for the whole ride.
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
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
