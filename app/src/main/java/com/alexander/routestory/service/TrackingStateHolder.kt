package com.alexander.routestory.service

import com.alexander.routestory.domain.TrackingUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the live tracking state shared between the service and the UI. */
@Singleton
class TrackingStateHolder @Inject constructor() {

    private val _uiState = MutableStateFlow(TrackingUiState())
    val uiState: StateFlow<TrackingUiState> = _uiState.asStateFlow()

    private val _trackSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val trackSaved: SharedFlow<Unit> = _trackSaved.asSharedFlow()

    fun update(transform: (TrackingUiState) -> TrackingUiState) = _uiState.update(transform)

    fun reset() {
        _uiState.value = TrackingUiState()
    }

    fun notifyTrackSaved() {
        _trackSaved.tryEmit(Unit)
    }
}