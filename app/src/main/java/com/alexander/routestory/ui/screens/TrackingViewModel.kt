package com.alexander.routestory.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.alexander.routestory.domain.TrackingUiState
import com.alexander.routestory.service.TrackRecordingService
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.service.TrackingStateHolder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

    /**
     * Sprint 14: seed the map with the last known location + one quick fix BEFORE the
     * recording starts, so the tile mosaic and the "me" dot appear immediately on
     * screen entry instead of waiting for the first recorded track point.
     */
    fun requestInitialFix(force: Boolean = false) {
        if (stateHolder.uiState.value.lastFix != null && !force) return
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val apply: (android.location.Location?) -> Unit = { loc ->
                if (loc != null && (force || stateHolder.uiState.value.lastFix == null)) {
                    stateHolder.update { it.copy(lastFix = loc.latitude to loc.longitude) }
                }
            }
            client.lastLocation.addOnSuccessListener(apply)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(apply)
        }
    }

    private fun sendAction(action: String, foreground: Boolean = false) {
        val intent = Intent(context, TrackRecordingService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    }
}