package com.alexander.routestory.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.domain.PlannedRoute
import com.alexander.routestory.domain.PlannedRoutePoint
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
    private val sfxPreferences: SfxPreferences,
    private val plannedRouteRepository: com.alexander.routestory.data.PlannedRouteRepository
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

    /**
     * Sprint 65: the planned route created from THIS ride, or null while there is none.
     * Drives the "уже сохранено" state of the button, so repeated taps cannot pile up
     * duplicate rows in "Сохранённые".
     */
    private val _routeSavedForRide = MutableStateFlow<Long?>(null)
    val routeSavedForRide: StateFlow<Long?> = _routeSavedForRide.asStateFlow()

    /**
     * Sprint 65: saves the finished ride as a reusable planned route.
     *
     * The real GPS track becomes the route geometry (simplified to [MAX_ROUTE_GEOMETRY]
     * points, because the geometry is stored as one "lat,lon;..." string). The waypoints
     * are the evenly spaced chain start -> ... -> end, so "Начать" from "Сохранённые" can
     * rebuild the same road with OSRM. No network call happens here: the recorded track is
     * already the answer, and OSRM would only re-route what we just drove.
     *
     * @return true when a new route was created, false when this ride already has one.
     */
    suspend fun saveAsPlannedRoute(track: Track): Boolean {
        _routeSavedForRide.value?.let { return false }
        if (track.points.size < 2) return false
        val geometry = simplify(track.points.map { PlannedRoutePoint(it.lat, it.lon, 0) })
        if (geometry.size < 2) return false
        val route = PlannedRoute(
            name = track.name,
            distanceMeters = track.distance,
            createdAt = System.currentTimeMillis(),
            geometry = geometry,
            waypoints = waypointsFor(track)
        )
        val id = runCatching { plannedRouteRepository.save(route) }.getOrNull() ?: return false
        _routeSavedForRide.value = id
        return true
    }

    /** Even stride thinning, first and last always kept (the route endpoints). */
    private fun simplify(points: List<PlannedRoutePoint>): List<PlannedRoutePoint> {
        if (points.size <= MAX_ROUTE_GEOMETRY) {
            return points.mapIndexed { i, p -> p.copy(order = i) }
        }
        val step = (points.size - 1).toDouble() / (MAX_ROUTE_GEOMETRY - 1)
        return (0 until MAX_ROUTE_GEOMETRY).map { i ->
            points[(i * step).toInt().coerceAtMost(points.size - 1)].copy(order = i)
        }
    }

    /** Chain of at most 10 evenly spaced points along the recorded track. */
    private fun waypointsFor(track: Track): List<PlannedRoutePoint> {
        val pts = track.points
        if (pts.size <= 2) {
            return pts.mapIndexed { i, p -> PlannedRoutePoint(p.lat, p.lon, i) }
        }
        val count = minOf(ROUTE_WAYPOINT_CHAIN, pts.size)
        val step = (pts.size - 1).toDouble() / (count - 1)
        return (0 until count).map { i ->
            val p = pts[(i * step).toInt().coerceAtMost(pts.size - 1)]
            PlannedRoutePoint(p.lat, p.lon, i)
        }
    }

    init {
        viewModelScope.launch {
            val loaded = repository.getTrack(trackId)
            if (loaded == null) _notFound.value = true else _track.value = loaded
            // Sprint 65: the ViewModel is recreated every time the ride is opened from
            // History, so the "already saved" state is re-derived from the database instead
            // of living only in memory - that is what makes the button truly idempotent.
            if (loaded != null) {
                val existing = runCatching {
                    plannedRouteRepository.observeAll().first()
                }.getOrDefault(emptyList())
                _routeSavedForRide.value = existing.firstOrNull { matches(it, loaded) }?.id
            }
        }
    }

    /**
     * Sprint 65: a route is "from this ride" when it carries the ride name and its
     * distance (rounded to metres - two different rides of the same name keep their own
     * rows). Deliberately simple: no schema change, no sourceTrackId column.
     */
    private fun matches(
        route: com.alexander.routestory.domain.PlannedRoute,
        track: Track
    ): Boolean =
        route.name == track.name &&
            kotlin.math.abs(route.distanceMeters - track.distance) < 1f

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
     * Sprint 63: the clip length is not passed in - the export always resolves it from
     * distance, photos and the encode budget.
     */
    fun createVideo(track: Track, keepSfx: Boolean) {
        if (videoStateHolder.state.value is VideoExportState.Running) return
        val intent = Intent(context, VideoExportService::class.java)
            .putExtra(VideoExportService.EXTRA_TRACK_ID, track.id)
        // Sprint 42: optional custom BGM (copied file); absent -> res/raw/bgm.wav.
        _musicPath.value?.let { intent.putExtra(VideoExportService.EXTRA_BGM_PATH, it) }
        // Sprint 43: SFX toggle - when off the cues are muted, BGM stays.
        intent.putExtra(VideoExportService.EXTRA_KEEP_SFX, keepSfx)
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

        /**
         * Sprint 65: the planned route geometry is stored as one "lat,lon;..." string in
         * Room, so a long ride is thinned to this many points before it is written. The
         * recorded track has a point every few metres; 400 is far more detail than the
         * builder map needs, and keeps the row small.
         */
        const val MAX_ROUTE_GEOMETRY = 400

        /** Waypoint chain length for a route saved from a ride (OSRM accepts 2..10). */
        const val ROUTE_WAYPOINT_CHAIN = 10
    }
}