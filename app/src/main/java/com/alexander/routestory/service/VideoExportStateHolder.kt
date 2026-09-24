package com.alexander.routestory.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** State of a video export that survives navigation and rotation. */
sealed class VideoExportState {
    data object Idle : VideoExportState()
    data class Preparing(val trackId: Long) : VideoExportState()
    data class Running(val trackId: Long, val progress: Float) : VideoExportState()
    data class Done(val trackId: Long, val file: File) : VideoExportState()
    data class Failed(val trackId: Long) : VideoExportState()
}

@Singleton
class VideoExportStateHolder @Inject constructor() {

    private val _state = MutableStateFlow<VideoExportState>(VideoExportState.Idle)
    val state: StateFlow<VideoExportState> = _state.asStateFlow()

    /** Currently running export, used for cancellation. Volatile: cancelled/reported from different threads. */
    @Volatile
    var job: Job? = null

    fun setPreparing(trackId: Long) {
        _state.value = VideoExportState.Preparing(trackId)
    }

    fun setRunning(trackId: Long, progress: Float) {
        // A late progress report from an encode whose job was already cancelled must
        // never resurrect the Running state: cancel() drops the job reference, so a
        // report is only accepted while the job that produced it is still active.
        // (Without this the Cancel dialog blinked away and came back frozen — the
        // "cancel works only on the second tap" defect.)
        val current = job
        if (current == null || !current.isActive) return
        // Never let the progress jump backwards (e.g. the snapshot phase reporting a
        // small value after an earlier frame already showed a higher one).
        val prev = (_state.value as? VideoExportState.Running)?.takeIf { it.trackId == trackId }?.progress ?: -1f
        if (progress <= prev) return
        _state.value = VideoExportState.Running(trackId, progress)
    }

    fun setDone(trackId: Long, file: File) {
        _state.value = VideoExportState.Done(trackId, file)
    }

    fun setFailed(trackId: Long) {
        _state.value = VideoExportState.Failed(trackId)
    }

    fun reset() {
        _state.value = VideoExportState.Idle
    }

    /**
     * Cancels the running export job and returns the UI to Idle immediately, in one
     * step: the job is dropped so [setRunning] ignores any further reports from it
     * (late onProgress callbacks from the synchronous frame loop used to flip the
     * state back to Running — the flicker + second-tap defect).
     */
    fun cancel() {
        job?.cancel()
        job = null
        reset()
    }
}