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

private val Context.proDataStore by preferencesDataStore(name = "pro_settings")

/** Local storage of the Pro purchase flag. */
@Singleton
class ProRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val proKey = booleanPreferencesKey("pro")

    val isPro: Flow<Boolean> = context.proDataStore.data.map { prefs -> prefs[proKey] ?: false }

    suspend fun setPro(enabled: Boolean) {
        context.proDataStore.edit { it[proKey] = enabled }
    }
}