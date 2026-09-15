package com.alexander.routestory.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackRepository
import com.alexander.routestory.utils.GpxExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>(ARG_TRACK_ID) ?: -1L

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _exportResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val exportResult: SharedFlow<Boolean> = _exportResult.asSharedFlow()

    init {
        viewModelScope.launch { _track.value = repository.getTrack(trackId) }
    }

    fun deleteTrack() {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    fun exportGpx(uri: Uri) {
        val current = _track.value ?: return
        viewModelScope.launch {
            val result = runCatching {
                val gpx = GpxExporter.buildGpx(current)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(gpx.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
            }
            _exportResult.tryEmit(result.isSuccess)
        }
    }

    companion object {
        const val ARG_TRACK_ID = "trackId"
    }
}