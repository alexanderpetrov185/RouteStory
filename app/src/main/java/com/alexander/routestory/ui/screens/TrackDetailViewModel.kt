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
import com.alexander.routestory.data.SfxPreferences
import com.alexander.routestory.service.VideoExportState
import com.alexander.routestory.service.VideoExportStateHolder
import com.alexander.routestory.service.VideoExportService
import android.content.Intent
import androidx.core.content.ContextCompat
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
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: TrackRepository,
    private val videoStateHolder: VideoExportStateHolder,
    private val proRepository: ProRepository,
    private val sfxPreferences: SfxPreferences
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>(ARG_TRACK_ID) ?: -1L

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    val videoState: StateFlow<VideoExportState> = videoStateHolder.state

    val isPro: StateFlow<Boolean> = proRepository.isPro
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when the ride no longer exists (deleted from history after it was stored). */
    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repository.getTrack(trackId)
            if (loaded == null) _notFound.value = true else _track.value = loaded
        }
    }

    fun deleteTrack() {
        viewModelScope.launch {
            repository.deleteTrack(trackId)
            // The archived video belongs to the deleted ride only.
            File(context.filesDir, "videos").resolve("track_$trackId.mp4").delete()
        }
    }

    fun renameCurrent(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.renameTrack(trackId, trimmed) }
            _track.value = repository.getTrack(trackId)
        }
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

    /**
     * Starts the foreground VideoExportService: it loads the track, resolves the limits
     * and runs the export, so a swipe from recents cannot kill the process mid-encode.
     */
    fun createVideo(track: Track, keepSfx: Boolean, targetTotalSec: Int? = null) {
        if (videoStateHolder.state.value is VideoExportState.Running) return
        val intent = Intent(context, VideoExportService::class.java)
            .putExtra(VideoExportService.EXTRA_TRACK_ID, track.id)
        // Sprint 42: optional custom BGM (copied file); absent -> res/raw/bgm.wav.
        _musicPath.value?.let { intent.putExtra(VideoExportService.EXTRA_BGM_PATH, it) }
        // Sprint 43: SFX toggle - when off the cues are muted, BGM stays.
        intent.putExtra(VideoExportService.EXTRA_KEEP_SFX, keepSfx)
        // Sprint 49: explicit total clip length (30/60/120/180 s); null = auto.
        targetTotalSec?.let { intent.putExtra(VideoExportService.EXTRA_TARGET_SEC, it) }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Sprint 43: user preference "keep SFX in the exported video" (default on). */
    val keepSfx: StateFlow<Boolean> = sfxPreferences.keepSfx
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setKeepSfx(enabled: Boolean) {
        viewModelScope.launch { sfxPreferences.setKeepSfx(enabled) }
    }

    fun cancelVideo() {
        videoStateHolder.cancel()
    }

    fun consumeVideoResult() {
        videoStateHolder.reset()
    }

    fun saveVideoTo(uri: Uri, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                }
            }
        }
    }

    /** Previously exported video for a ride, or null when none was made yet. */
    fun archivedVideo(trackId: Long): File? =
        File(File(context.filesDir, "videos"), "track_$trackId.mp4")
            .takeIf { it.isFile && it.length() > 0L }

    /** Sprint 42: saves the ride photo copy to a SAF destination (no permissions). */
    fun savePhotoTo(uri: Uri, photo: TrackPhoto) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    File(photo.uri).inputStream().use { it.copyTo(output) }
                }
            }
        }
    }

    /**
     * Sprint 42: custom BGM for the NEXT export of this ride. The SAF grant is
     * one-shot, so the track is copied into filesDir/music immediately; null =
     * bundled res/raw/bgm.wav (any decode failure later also falls back to it).
     */
    private val _musicPath = MutableStateFlow<String?>(null)
    val musicPath: StateFlow<String?> = _musicPath.asStateFlow()

    fun pickMusic(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val display = context.contentResolver
                    .query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                val ext = display?.substringAfterLast('.', "mp3") ?: "mp3"
                val dest = File(File(context.filesDir, "music"), "track_$trackId.$ext")
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                _musicPath.value = dest.absolutePath
            }.onFailure {
                _musicPath.value = null
            }
        }
    }

    companion object {
        const val ARG_TRACK_ID = "trackId"
    }
}