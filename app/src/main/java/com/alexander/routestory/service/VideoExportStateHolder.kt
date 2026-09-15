package com.alexander.routestory.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** State of a video export that survives navigation and rotation. */
sealed class VideoExportState {
    data object Idle : VideoExportState()
    data class Running(val progress: Float) : VideoExportState()
    data class Done(val file: File) : VideoExportState()
    data object Failed : VideoExportState()
}

@Singleton
class VideoExportStateHolder @Inject constructor() {

    private val _state = MutableStateFlow<VideoExportState>(VideoExportState.Idle)
    val state: StateFlow<VideoExportState> = _state.asStateFlow()

    /** Own scope so the export survives configuration changes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Currently running export, used for cancellation. */
    var job: Job? = null

    fun setRunning(progress: Float) {
        _state.value = VideoExportState.Running(progress)
    }

    fun setDone(file: File) {
        _state.value = VideoExportState.Done(file)
    }

    fun setFailed() {
        _state.value = VideoExportState.Failed
    }

    fun reset() {
        _state.value = VideoExportState.Idle
    }
}