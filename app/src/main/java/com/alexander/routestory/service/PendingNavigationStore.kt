package com.alexander.routestory.service

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers a ride that was stored while the app window was gone (Stop pressed in the
 * notification shade). Persisted, so even a cold start lands on the ride summary instead of
 * silently showing Home.
 */
@Singleton
class PendingNavigationStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("pending_navigation", Context.MODE_PRIVATE)
    }

    /** Called by the service right after a ride has been written to the database. */
    fun setSummaryTrack(trackId: Long) {
        prefs.edit { putLong(KEY_SUMMARY_TRACK_ID, trackId) }
    }

    /** Reads the pending id and clears it so it is used at most once. */
    fun takeSummaryTrack(): Long? {
        val id = prefs.getLong(KEY_SUMMARY_TRACK_ID, 0L)
        if (id <= 0L) return null
        prefs.edit { remove(KEY_SUMMARY_TRACK_ID) }
        return id
    }

    fun clear() {
        prefs.edit { remove(KEY_SUMMARY_TRACK_ID) }
    }

    private companion object {
        const val KEY_SUMMARY_TRACK_ID = "summaryTrackId"
    }
}