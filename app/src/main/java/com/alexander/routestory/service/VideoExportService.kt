package com.alexander.routestory.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alexander.routestory.MainActivity
import com.alexander.routestory.R
import com.alexander.routestory.data.ProRepository
import com.alexander.routestory.domain.TrackRepository
import com.alexander.routestory.video.VideoExportUseCase
import com.alexander.routestory.video.VideoLimits
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Foreground service for the on-device video export.
 *
 * The export used to run in a singleton CoroutineScope: swiping the app from recents
 * killed the (empty) process and the export died half-way. A dataSync foreground
 * service keeps the process alive, shows a non-swipeable progress notification and
 * survives leaving the app. The recording service is untouched: separate ids, channel
 * and type, so both can run at the same time.
 */
@AndroidEntryPoint
class VideoExportService : LifecycleService() {

    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var proRepository: ProRepository
    @Inject lateinit var videoExportUseCase: VideoExportUseCase
    @Inject lateinit var stateHolder: VideoExportStateHolder

    private var exportJob: Job? = null
    private var lastNotifyMs = 0L

    /** True once the terminal "done" notification replaced the progress one. */
    private var doneNotificationShown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeState()
    }

    /**
     * Cancels the progress notification on every path that does not post the "done" one
     * (in-app cancel, shade cancel, failure, process kill): it is ongoing and cannot be
     * swiped away, so leaving it behind blocks the shade.
     */
    override fun onDestroy() {
        if (!doneNotificationShown) {
            runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            // Same one-shot path as the in-app Cancel button: the holder drops the job
            // and stops accepting progress, then the service stops itself.
            ACTION_CANCEL -> {
                stateHolder.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Must happen before anything async: startForegroundService callers get
                // a timeout if the service never enters the foreground state.
                goForeground()
                val trackId = intent?.getLongExtra(EXTRA_TRACK_ID, -1L) ?: -1L
                val bgmPath = intent?.getStringExtra(EXTRA_BGM_PATH)
                // Sprint 43: SFX toggle rides the intent; absent extra = keep SFX (default on).
                val keepSfx = intent?.getBooleanExtra(EXTRA_KEEP_SFX, true) ?: true
                startExport(trackId, bgmPath, keepSfx)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startExport(trackId: Long, bgmPath: String? = null, keepSfx: Boolean = true) {
        // Single export at a time; a second START while one is running is a no-op.
        if (exportJob?.isActive == true || stateHolder.state.value is VideoExportState.Preparing || stateHolder.state.value is VideoExportState.Running) return
        if (trackId <= 0L) {
            stopSelf()
            return
        }
        exportJob = lifecycleScope.launch {
            try {
                val track = repository.getTrack(trackId)
                if (track == null || track.points.isEmpty()) {
                    Log.w(TAG, "export skipped: track $trackId missing or empty")
                    stateHolder.setFailed(trackId)
                    return@launch
                }
                    stateHolder.setPreparing(trackId)
                val limits = VideoLimits.fitEncode(
                    proRepository.isPro.first(),
                    track.distance,
                    track.photos.size
                )
                // Sprint 42: custom BGM path is optional; the encode falls back to bgm.wav.
                // Sprint 43: keepSfx=false mutes the SFX cues (shutter/whoosh) but keeps BGM.
                val file = videoExportUseCase.export(track, limits, bgmPath, keepSfx) {
                    stateHolder.setRunning(trackId, it)
                }
                if (file != null) {
                    stateHolder.setDone(trackId, file)
                    // Keep a watchable copy; the Done dialog only previews the temp file.
                    archiveVideo(track.id, file)
                    showDoneNotification(trackId)
                } else if (stateHolder.state.value !is VideoExportState.Idle) {
                    stateHolder.setFailed(trackId)
                }
            } finally {
                // Runs on normal completion, failure and shade cancellation alike.
                stopSelf()
            }
        }
        stateHolder.job = exportJob
    }

    /** Mirrors export progress into the foreground notification (throttled). */
    private fun observeState() {
        lifecycleScope.launch {
            var lastPercent = -1
            stateHolder.state.collect { state ->
                if (state is VideoExportState.Running) {
                    val percent = (state.progress * 100).toInt()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (percent != lastPercent && now - lastNotifyMs >= 500) {
                        lastPercent = percent
                        lastNotifyMs = now
                        notifyProgress(percent)
                    }
                }
            }
        }
    }

    private fun goForeground() {
        NotificationManagerCompat.from(this).cancel(DONE_NOTIFICATION_ID)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(percent = -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyProgress(percent: Int) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(percent))
        }.onFailure { Log.w(TAG, "progress notification failed", it) }
    }

    private fun showDoneNotification(trackId: Long) {
        runCatching {
            // Order matters. Detaching the foreground notification
            // (STOP_FOREGROUND_DETACH) left the ongoing, non-swipeable
            // "Создание видео… 100%" in the shade forever: it has to be cancelled and the
            // foreground state removed BEFORE the dismissible "done" one is posted.
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).notify(
                DONE_NOTIFICATION_ID,
                NotificationCompat.Builder(this, DONE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.video_notif_title))
                    .setContentText(getString(R.string.video_notif_done))
                    .setContentIntent(openTrackIntent(trackId))
                    // Tapping opens the ride (Sprint 47: no autoplay from notifications).
                    .addAction(0, getString(R.string.action_watch_video), openTrackIntent(trackId))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
            )
            doneNotificationShown = true
        }.onFailure { Log.w(TAG, "done notification failed", it) }
    }

    /**
     * Opens the ride that was just rendered: MainActivity turns the extra into a
     * navigation to the detail screen (Sprint 47: no autoplay from notifications).
     */
    private fun openTrackIntent(trackId: Long): PendingIntent = PendingIntent.getActivity(
        this, trackId.toInt(),
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TRACK_ID, trackId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Plain "bring the app back" intent for the progress notification. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(percent: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (percent < 0) {
            getString(R.string.video_notif_preparing)
        } else {
            getString(R.string.video_notif_running, percent)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.video_notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.action_cancel), cancelIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (percent < 0) setProgress(0, 0, true) else setProgress(100, percent, false)
            }
            .build()
    }

    /** Persists the last export so the ride keeps a watchable copy (viewing is free). */
    private suspend fun archiveVideo(trackId: Long, file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(filesDir, "videos").apply { mkdirs() }
            file.copyTo(File(dir, "track_$trackId.mp4"), overwrite = true)
        }
        Unit
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.video_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Separate channel: the progress one is intentionally silent, the finished
        // notification should show up and be swipeable.
        manager.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL_ID,
                getString(R.string.video_notif_done_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val ACTION_CANCEL = "com.alexander.routestory.action.CANCEL_VIDEO_EXPORT"
        const val EXTRA_TRACK_ID = "trackId"
        /** Sprint 42: optional path of a user-picked BGM file (null/absent -> bgm.wav). */
        const val EXTRA_BGM_PATH = "bgmPath"
        /** Sprint 43: when false, the SFX cues (shutter/whoosh) are muted; default true. */
        const val EXTRA_KEEP_SFX = "keepSfx"
        private const val CHANNEL_ID = "video_export"
        /** Finished-clip channel: IMPORTANCE_DEFAULT so the "done" notification is visible. */
        private const val DONE_CHANNEL_ID = "video_export_done"
        /** Distinct from the recording service (id 1, channel "tracking"). */
        private const val NOTIFICATION_ID = 2001
        private const val DONE_NOTIFICATION_ID = 2002
        private const val TAG = "VideoExportService"
    }
}
