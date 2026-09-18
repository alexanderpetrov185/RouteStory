package com.alexander.routestory.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.data.ProRepository
import com.alexander.routestory.domain.TrackingUiState
import com.alexander.routestory.service.TrackRecordingService
import com.alexander.routestory.service.TrackingStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home screen state: only needs to know whether an interrupted ride can be resumed, so the
 * "continue / discard" question is asked as soon as the app opens, not after navigating to
 * the tracking screen.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: TrackingStateHolder,
    private val proRepository: ProRepository
) : ViewModel() {

    val uiState: StateFlow<TrackingUiState> = stateHolder.uiState

    fun setDiscardConfirmed(enabled: Boolean) {
        viewModelScope.launch {
            proRepository.setDiscardConfirmed(enabled)
        }
    }

    /** Asks the recording service whether a draft exists on disk. */
    fun checkDraft() = sendAction(TrackRecordingService.ACTION_CHECK_DRAFT)

    /** Restores the draft and keeps recording (one tap, straight back to RECORDING). */
    fun resumeDraft() = sendAction(TrackRecordingService.ACTION_RESTORE_DRAFT, foreground = true)

    fun discardDraft() {
        sendAction(TrackRecordingService.ACTION_DISCARD_DRAFT)
        dismissDraftNotification()
    }

    private fun dismissDraftNotification() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(DRAFT_NOTIFICATION_ID)
        }
    }

    private fun sendAction(action: String, foreground: Boolean = false) {
        val intent = Intent(context, TrackRecordingService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    }

    private companion object {
        /** Must match TrackRecordingService.DRAFT_NOTIFICATION_ID. */
        const val DRAFT_NOTIFICATION_ID = 1001
    }
}