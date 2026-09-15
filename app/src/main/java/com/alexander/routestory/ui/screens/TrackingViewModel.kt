package com.alexander.routestory.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.alexander.routestory.domain.TrackingUiState
import com.alexander.routestory.service.TrackRecordingService
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.service.TrackingStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TrackingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: TrackingStateHolder
) : ViewModel() {

    val uiState: StateFlow<TrackingUiState> = stateHolder.uiState
    val saveOutcome: StateFlow<TrackSaveOutcome?> = stateHolder.saveOutcome

    fun start() = sendAction(TrackRecordingService.ACTION_START, foreground = true)
    fun pause() = sendAction(TrackRecordingService.ACTION_PAUSE)
    fun resume() = sendAction(TrackRecordingService.ACTION_START, foreground = true)
    fun stop() = sendAction(TrackRecordingService.ACTION_STOP)

    fun consumeSaveOutcome() = stateHolder.consumeSaveOutcome()

    private fun sendAction(action: String, foreground: Boolean = false) {
        val intent = Intent(context, TrackRecordingService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    }
}