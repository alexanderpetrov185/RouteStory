package com.alexander.routestory.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.alexander.routestory.data.ProRepository
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.utils.LocationFixProvider
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import com.alexander.routestory.domain.TrackingUiState
import com.alexander.routestory.service.TrackRecordingService
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.service.TrackingStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TrackingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: TrackingStateHolder,
    private val savedState: SavedStateHandle,
    private val proRepository: ProRepository,
    private val plannedRouteRepository: com.alexander.routestory.data.PlannedRouteRepository,
    private val fixProvider: LocationFixProvider
) : ViewModel() {
    val isPro = proRepository.isPro.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        savedState.get<Long>("routeId")?.let { loadPlannedRoute(it) }
    }

    fun loadPlannedRoute(id: Long) {
        viewModelScope.launch {
            val cached = stateHolder.uiState.value.plannedRoute
            if (cached?.id == id) return@launch
            plannedRouteRepository.getById(id)?.let { stateHolder.setPlannedRoute(it) }
        }
    }
    private val _photoErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoErrors = _photoErrors.asSharedFlow()

    /** Photos allowed per ride on the free tier. */
    val photoLimit: Int get() = if (isPro.value) Int.MAX_VALUE else MAX_FREE_PHOTOS

    fun preparePhoto(): android.net.Uri? {
        val state = uiState.value
        if (state.state == TrackingState.IDLE || state.capturePending ||
            (!isPro.value && state.photos.size >= MAX_FREE_PHOTOS)) return null
        return try {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File.createTempFile("ride_", ".jpg", dir)
            savedState["photoPath"] = file.absolutePath
            savedState["photoSession"] = state.sessionId
            savedState["photoTime"] = System.currentTimeMillis()
            savedState["photoLat"] = state.lastFix?.first
            savedState["photoLon"] = state.lastFix?.second
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            stateHolder.update { it.copy(capturePending = true) }
            uri
        } catch (_: Exception) {
            finishPhoto(false)
            _photoErrors.tryEmit(Unit)
            null
        }
    }

    fun checkDraft() = sendAction(TrackRecordingService.ACTION_CHECK_DRAFT)
    fun restoreDraft() = sendAction(TrackRecordingService.ACTION_RESTORE_DRAFT, foreground = true)
    fun discardDraft() = sendAction(TrackRecordingService.ACTION_DISCARD_DRAFT)

    /** Persists the "don't ask again" preference via ProRepository. */
    fun setDiscardConfirmed(enabled: Boolean) {
        viewModelScope.launch {
            proRepository.setDiscardConfirmed(enabled)
        }
    }

    val photoPreview = savedState.getStateFlow<String?>("photoPreview", null)
    fun captureResult(success: Boolean) {
        if (success) savedState["photoPreview"] = savedState.get<String>("photoPath")
        else finishPhoto(false)
    }

    fun retakePhoto(): android.net.Uri? {
        savedState.get<String>("photoPath")?.let { File(it).delete() }
        savedState["photoPreview"] = null
        savedState.remove<String>("photoPath")
        stateHolder.update { it.copy(capturePending = false) }
        return preparePhoto()
    }

    fun finishPhoto(success: Boolean) {
        savedState["photoPreview"] = null
        val path = savedState.get<String>("photoPath") ?: return
        val session = savedState.get<String>("photoSession")
        val timestamp = savedState.get<Long>("photoTime") ?: System.currentTimeMillis()
        val lat = savedState.get<Double>("photoLat")
        val lon = savedState.get<Double>("photoLon")
        savedState.remove<String>("photoPath")
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) {
                val file = File(path)
                if (!success || file.length() == 0L) null else runCatching {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(path, options)
                    require(options.outWidth > 0 && options.outHeight > 0)
                    // External camera does not return shutter time. Prefer plausible EXIF time.
                    val exifTime = runCatching {
                        val raw = ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        raw?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.time }
                    }.getOrNull()
                    val time = exifTime?.takeIf { it in timestamp..System.currentTimeMillis() } ?: timestamp
                    TrackPhoto(trackId = 0, uri = path, timestamp = time, latitude = lat, longitude = lon)
                }.getOrNull()
            }
            val state = uiState.value
            val accepted = photo != null && state.sessionId == session && state.state != TrackingState.IDLE &&
                (isPro.value || state.photos.size < MAX_FREE_PHOTOS)
            if (accepted) stateHolder.update { it.copy(photos = it.photos + photo!!) }
            else withContext(Dispatchers.IO) { File(path).delete() }
            if (state.sessionId == session) stateHolder.update { it.copy(capturePending = false) }
            if (success && !accepted) _photoErrors.emit(Unit)
        }
    }

    val uiState: StateFlow<TrackingUiState> = stateHolder.uiState
    val saveOutcome: StateFlow<TrackSaveOutcome?> = stateHolder.saveOutcome

    fun start() = sendAction(TrackRecordingService.ACTION_START, foreground = true)
    fun pause() = sendAction(TrackRecordingService.ACTION_PAUSE)
    fun resume() = sendAction(TrackRecordingService.ACTION_START, foreground = true)
    /** Ignores a repeated Stop once the ride is no longer active. */
    fun stop() {
        if (stateHolder.uiState.value.state == TrackingState.IDLE) return
        sendAction(TrackRecordingService.ACTION_STOP)
    }

    fun consumeSaveOutcome() = stateHolder.consumeSaveOutcome()

    /**
     * Sprint 14: seed the map with the last known location + one quick fix BEFORE the
     * recording starts, so the tile mosaic and the "me" dot appear immediately on
     * screen entry instead of waiting for the first recorded track point.
     * Sprint 61: shared LocationFixProvider (same helper as RouteBuilder).
     */
    fun requestInitialFix(force: Boolean = false) {
        if (stateHolder.uiState.value.lastFix != null && !force) return
        viewModelScope.launch {
            runCatching { fixProvider.requestFix(context, force) }.getOrNull()?.let { fix ->
                if (force || stateHolder.uiState.value.lastFix == null) {
                    stateHolder.update { it.copy(lastFix = fix) }
                }
            }
        }
    }

    private fun sendAction(action: String, foreground: Boolean = false) {
        val intent = Intent(context, TrackRecordingService::class.java).setAction(action)
        if (foreground) ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    }

    companion object {
        /** Free tier is capped to 10 in-ride photos. */
        const val MAX_FREE_PHOTOS = 10
    }
}