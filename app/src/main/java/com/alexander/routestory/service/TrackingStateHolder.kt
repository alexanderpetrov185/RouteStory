package com.alexander.routestory.service

import com.alexander.routestory.domain.TrackingUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a finished recording session, delivered to the UI. */
sealed interface TrackSaveOutcome {
    data class Saved(val trackId: Long) : TrackSaveOutcome
    object Discarded : TrackSaveOutcome
}

/** Holds the live tracking state shared between the service and the UI. */
@Singleton
class TrackingStateHolder @Inject constructor() {

    private val _uiState = MutableStateFlow(TrackingUiState())
    val uiState: StateFlow<TrackingUiState> = _uiState.asStateFlow()

    private val _saveOutcome = MutableStateFlow<TrackSaveOutcome?>(null)
    val saveOutcome: StateFlow<TrackSaveOutcome?> = _saveOutcome.asStateFlow()

    fun update(transform: (TrackingUiState) -> TrackingUiState) = _uiState.update(transform)

    fun setDraftAvailable(available: Boolean) {
        _uiState.update { it.copy(draftAvailable = available) }
    }

    fun reset() {
        _uiState.value = TrackingUiState()
    }

    fun notifySaved(trackId: Long) {
        _saveOutcome.value = TrackSaveOutcome.Saved(trackId)
    }

    fun notifyDiscarded() {
        _saveOutcome.value = TrackSaveOutcome.Discarded
    }

    fun consumeSaveOutcome() {
        _saveOutcome.value = null
    }
}