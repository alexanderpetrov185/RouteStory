package com.alexander.routestory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.service.PendingNavigationStore
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.service.TrackingStateHolder
import com.alexander.routestory.ui.navigation.RouteStoryNavHost
import com.alexander.routestory.ui.theme.RouteStoryTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var stateHolder: TrackingStateHolder
    @Inject lateinit var pendingNavigation: PendingNavigationStore

    /** Bumped by every notification tap that must open the recording screen. */
    private val openTrackingRequests = MutableStateFlow(0)

    /** Ride stored while no window existed: its summary is opened on this start. */
    private val pendingSummary = MutableStateFlow<Long?>(null)

    /**
     * Ride the export notification must open (with the video playing). Set by the tap on
     * "Видео готово", consumed by the nav host.
     */
    private val pendingTrackId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start after a Stop from the shade. While the process is still alive the save
        // outcome is already in memory, the nav host handles it and clears the marker.
        pendingSummary.value = if (stateHolder.saveOutcome.value is TrackSaveOutcome.Saved) {
            null
        } else {
            pendingNavigation.takeSummaryTrack()
        }
        handleIntent(intent)
        setContent {
            RouteStoryTheme {
                val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
                val saveOutcome by stateHolder.saveOutcome.collectAsStateWithLifecycle()
                val openRequests by openTrackingRequests.collectAsStateWithLifecycle()
                val summary by pendingSummary.collectAsStateWithLifecycle()
                val trackToOpen by pendingTrackId.collectAsStateWithLifecycle()
                RouteStoryNavHost(
                    rideState = uiState.state,
                    saveOutcome = saveOutcome,
                    openTrackingRequest = openRequests,
                    pendingSummaryTrackId = summary,
                    pendingTrackId = trackToOpen,
                    onPendingHandled = {
                        pendingSummary.value = null
                        pendingNavigation.clear()
                        stateHolder.consumeSaveOutcome()
                    },
                    onPendingTrackHandled = { pendingTrackId.value = null },
                    // Sprint 65: needed for the ephemeral plan of "Начать маршрут".
                    stateHolder = stateHolder
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TRACKING, false) == true) {
            intent.removeExtra(EXTRA_OPEN_TRACKING)
            openTrackingRequests.value += 1
        }
        // "Видео готово" notification tap: open that ride (no autoplay - Sprint 47).
        val renderedTrackId = intent?.getLongExtra(EXTRA_OPEN_TRACK_ID, -1L) ?: -1L
        if (renderedTrackId > 0L) {
            intent!!.removeExtra(EXTRA_OPEN_TRACK_ID)
            pendingTrackId.value = renderedTrackId
        }
    }

    companion object {
        /** Notification taps carry this extra so the app opens the recording screen. */
        const val EXTRA_OPEN_TRACKING = "com.alexander.routestory.extra.OPEN_TRACKING"

        /** Export-notification taps carry the rendered ride so it can be opened (no autoplay). */
        const val EXTRA_OPEN_TRACK_ID = "com.alexander.routestory.extra.OPEN_TRACK_ID"
    }
}
