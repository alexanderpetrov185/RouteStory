package com.alexander.routestory.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackRepository
import com.alexander.routestory.service.VideoExportState
import com.alexander.routestory.service.VideoExportStateHolder
import com.alexander.routestory.video.VideoExportUseCase
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
    private val repository: TrackRepository,
    private val videoExportUseCase: VideoExportUseCase,
    private val videoStateHolder: VideoExportStateHolder
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>(ARG_TRACK_ID) ?: -1L

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _exportResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val exportResult: SharedFlow<Boolean> = _exportResult.asSharedFlow()

    val videoState: StateFlow<VideoExportState> = videoStateHolder.state

    init {
        viewModelScope.launch { _track.value = repository.getTrack(trackId) }
    }

    fun deleteTrack() {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    fun addPhotos(uris: List<Uri>) {
        val current = _track.value ?: return
        viewModelScope.launch {
            uris.forEach { repository.addPhoto(current, it) }
            _track.value = repository.getTrack(trackId)
        }
    }

    fun deletePhoto(photo: TrackPhoto) {
        viewModelScope.launch {
            repository.deletePhoto(photo)
            _track.value = repository.getTrack(trackId)
        }
    }

    fun shiftPhotoTime(photo: TrackPhoto, deltaMinutes: Long) {
        viewModelScope.launch {
            repository.updatePhotoTimestamp(photo, photo.timestamp + deltaMinutes * 60000)
            _track.value = repository.getTrack(trackId)
        }
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

    fun createVideo(track: Track) {
        if (videoStateHolder.state.value is VideoExportState.Running) return
        videoStateHolder.job = videoStateHolder.scope.launch {
            val file = videoExportUseCase.export(track) { videoStateHolder.setRunning(it) }
            if (file != null) {
                videoStateHolder.setDone(file)
            } else if (videoStateHolder.state.value !is VideoExportState.Idle) {
                videoStateHolder.setFailed()
            }
        }
    }

    fun cancelVideo() {
        videoStateHolder.job?.cancel()
        videoStateHolder.reset()
    }

    fun consumeVideoResult() {
        videoStateHolder.reset()
    }

    fun saveVideoTo(uri: Uri) {
        val file = (videoStateHolder.state.value as? VideoExportState.Done)?.file ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                }
            }
        }
    }

    companion object {
        const val ARG_TRACK_ID = "trackId"
    }
}