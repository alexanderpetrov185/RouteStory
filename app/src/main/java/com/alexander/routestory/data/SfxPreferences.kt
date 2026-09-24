package com.alexander.routestory.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sfxDataStore by preferencesDataStore(name = "sfx_settings")

/**
 * Sprint 43: local preference for keeping the SFX cues (shutter clicks + whoosh)
 * in the exported video. Default ON.
 */
@Singleton
class SfxPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keepSfxKey = booleanPreferencesKey("keepSfx")

    /** True = mix the SFX cues into the BGM track (default). */
    val keepSfx: Flow<Boolean> =
        context.sfxDataStore.data.map { prefs -> prefs[keepSfxKey] ?: true }

    suspend fun setKeepSfx(enabled: Boolean) {
        context.sfxDataStore.edit { it[keepSfxKey] = enabled }
    }
}
