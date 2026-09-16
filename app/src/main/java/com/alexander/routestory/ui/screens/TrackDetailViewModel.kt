package com.alexander.routestory.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackRepository
import com.alexander.routestory.data.ProRepository
import com.alexander.routestory.service.VideoExportState
import com.alexander.routestory.video.VideoLimits
import com.alexander.routestory.service.VideoExportStateHolder
import com.alexander.routestory.video.VideoExportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
    private val videoExportUseCase: VideoExportUseCase,
    private val videoStateHolder: VideoExportStateHolder,
    private val proRepository: ProRepository
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>(ARG_TRACK_ID) ?: -1L

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    val videoState: StateFlow<VideoExportState> = videoStateHolder.state

    val isPro: StateFlow<Boolean> = proRepository.isPro
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun createVideo(track: Track) {
        if (videoStateHolder.state.value is VideoExportState.Running) return
        viewModelScope.launch {
            val limits = VideoLimits.resolve(proRepository.isPro.first())
            videoStateHolder.job = videoStateHolder.scope.launch {
                val file = videoExportUseCase.export(track, limits) { videoStateHolder.setRunning(it) }
            if (file != null) {
                videoStateHolder.setDone(file)
                } else if (videoStateHolder.state.value !is VideoExportState.Idle) {
                    videoStateHolder.setFailed()
                }
            }
        }
    }

    fun cancelVideo() {
        videoStateHolder.cancel()
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