package com.alexander.routestory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope

import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.service.VideoExportState
import com.alexander.routestory.utils.GeoUtils
import java.io.File
import android.content.Context

private fun stateTrackId(state: VideoExportState): Long? = when (state) {
    is VideoExportState.Preparing -> state.trackId
    is VideoExportState.Running -> state.trackId
    is VideoExportState.Done -> state.trackId
    is VideoExportState.Failed -> state.trackId
    VideoExportState.Idle -> null
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    onBack: () -> Unit,
    onOpenPro: () -> Unit = {},
    isSummary: Boolean = false,
    onDone: () -> Unit = onBack,
    viewModel: TrackDetailViewModel = hiltViewModel()
) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val trackMissing by viewModel.notFound.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Sprint 41: overwrite warning before re-rendering an existing video.
    var showRecreateConfirm by remember { mutableStateOf(false) }
    var showCreateVideoDialog by remember { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<TrackPhoto?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // Sprint 22: the ride screen is view-only by default; editing (photos, re-rendering

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    // Sprint 41: saving works for BOTH the fresh Done result and the archived copy
    // (any time from the watch dialog), so the launcher takes an explicit target.
    var saveTarget by remember { mutableStateOf<File?>(null) }
    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val target = saveTarget
        if (uri != null && target != null) viewModel.saveVideoTo(uri, target)
    }

    // Sprint 42: photo save to a SAF destination (no permissions needed).
    var savePhotoTarget by remember { mutableStateOf<TrackPhoto?>(null) }
    val savePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val photo = savePhotoTarget
        if (uri != null && photo != null) viewModel.savePhotoTo(uri, photo)
    }
    // Sprint 42: pick a custom BGM track once; the choice rides the next export.
    val musicPath by viewModel.musicPath.collectAsStateWithLifecycle()
    val pickMusicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.pickMusic(it) } }
    // Sprint 43: SFX toggle for the next export (default on, persisted).
    val keepSfx by viewModel.keepSfx.collectAsStateWithLifecycle()

    // The summary screen is opened right after a successful save.
    LaunchedEffect(isSummary) {
        if (isSummary) snackbarHostState.showSnackbar(context.getString(R.string.track_saved))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isSummary) R.string.trip_summary else R.string.detail_title)) },
                navigationIcon = {
                    if (!isSummary) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val current = track
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                if (trackMissing) {
                    // The ride was removed while it was being opened: no endless spinner.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.track_missing))
                        Button(onClick = onDone) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            val scrollState = rememberScrollState()
            val photoPositions = remember(current.photos, current.points) {
                photoPositionsOf(current.points, current.photos)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // A fixed height instead of weight: a weighted child collapses to zero
                // height inside a vertically scrollable column. Hidden for a ride that has
                // no accepted fix at all (there is nothing to draw, only empty ocean).
                if (current.points.isNotEmpty()) {
                    RouteMapPlaceholder(
                        points = current.points,
                        photoPositions = photoPositions,
                        onPhotoTap = { index -> selectedPhoto = current.photos.getOrNull(index) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            current.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSummary || isPro) {
                            TextButton(onClick = { showRenameDialog = true }) {
                                Text(stringResource(R.string.action_rename))
                            }
                        }
                    }
                    Text(
                        // Sprint 68: the whole ride window (start-end) in the device time zone.
                        GeoUtils.formatTimeRange(current.startTime, current.endTime),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DetailStat(stringResource(R.string.stat_distance), GeoUtils.formatDistance(current.distance))
                    DetailStat(stringResource(R.string.stat_time), GeoUtils.formatDuration(current.duration))
                    DetailStat(stringResource(R.string.stat_points), current.points.size.toString())
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DetailStat(stringResource(R.string.stat_avg_speed), GeoUtils.formatSpeed(avgSpeed(current)))
                    DetailStat(stringResource(R.string.stat_max_speed), GeoUtils.formatSpeed(maxSpeed(current)))
                }
                // Sprint 70: climb and descent side by side in their own row, so a
                // hilly ride shows both ("up" alone used to read as ever-growing).
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DetailStat(
                        stringResource(R.string.stat_elevation),
                        "\u25B2 " + GeoUtils.formatElevation(GeoUtils.totalAscent(current.points))
                    )
                    DetailStat(
                        stringResource(R.string.stat_descent),
                        "\u25BC " + GeoUtils.formatElevation(GeoUtils.totalDescent(current.points))
                    )
                }
                // Sprint 69: high-quality trip photo (OSM scheme / Esri satellite).
                TripPhotoCard(
                    track = current,
                    modifier = Modifier.fillMaxWidth()
                )
                // Sprint 42: inline video block (progress while Running, embedded
                // player when Done/archived) right under the map and stats.
                VideoBlock(
                    trackId = current.id,
                    trackName = current.name,
                    onSave = { f ->
                        saveTarget = f
                        saveVideoLauncher.launch(
                            "routestory_video_" + current.id + ".mp4"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.photos) { photo ->
                        PhotoThumb(photo = photo, onClick = { selectedPhoto = photo })
                    }
                }
                // Sprint 42: the archived copy also belongs to the inline player block,
                // so the create button keys off Done state only.
                val archivedVideo = remember(current.id, videoState::class) {
                    viewModel.archivedVideo(current.id)
                }
                Button(
                    onClick = {
                        // Sprint 41: an existing video is only overwritten after a warning.
                        if (archivedVideo != null) showRecreateConfirm = true
                        else showCreateVideoDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // A ride without points has nothing to animate.
                    enabled = videoState !is VideoExportState.Running && current.points.isNotEmpty()
                ) {
                    Text(
                        stringResource(
                            if (archivedVideo != null) R.string.action_recreate_video
                            else R.string.action_create_video
                        )
                    )
                }
                // Sprint 43: SFX toggle — "Оставить звуковые эффекты" (default on).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = keepSfx,
                        onCheckedChange = { viewModel.setKeepSfx(it) }
                    )
                    Text(
                        text = stringResource(R.string.video_sfx_toggle),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Watch: available to everyone once a rendered video exists (free feature).
                // Sprint 42: playback lives in the inline VideoBlock (progress while
                // running, player when done/archived) - no modal preview anymore.
                // Both flows show "Добавить фото"; the detail screen keeps the Pro gate.
                if (isSummary) {
                    OutlinedButton(
                        onClick = {
                            pickPhotosLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_add_photos))
                    }
                } else {
                    // Sprint 47: no edit mode on a finished ride - "Добавить фото"
                    // replaces "Изменить" and keeps the same Pro gate.
                    OutlinedButton(
                        onClick = {
                            if (isPro) {
                                pickPhotosLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } else {
                                screenScope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.pro_required_edit))
                                }
                                onOpenPro()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_add_photos))
                    }
                }
                Text(
                    text = stringResource(R.string.photo_vertical_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (isSummary) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }

    val renameTarget = track
    if (showRenameDialog && renameTarget != null) {
        var draft by remember(renameTarget.id, renameTarget.name) { mutableStateOf(renameTarget.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCurrent(draft)
                    showRenameDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    selectedPhoto?.let { photo ->
        AlertDialog(
            onDismissRequest = { selectedPhoto = null },
            title = { Text(GeoUtils.formatDate(photo.timestamp)) },
            text = {
                val bigBitmap = rememberPhotoBitmap(photo.uri, 1024)
                if (bigBitmap != null) {
                    Image(
                        bitmap = bigBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(photo.uri)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedPhoto = null
                    viewModel.deletePhoto(photo)
                }) {
                    Text(stringResource(R.string.action_delete_photo), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                // Sprint 42: photos can also be saved to the device (SAF, no permission).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        savePhotoTarget = photo
                        savePhotoLauncher.launch("routestory_photo_" + photo.timestamp + ".jpg")
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                    TextButton(onClick = { selectedPhoto = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    if (videoState is VideoExportState.Failed) {
        LaunchedEffect(videoState) {
            snackbarHostState.showSnackbar(context.getString(R.string.video_error))
            viewModel.consumeVideoResult()
        }
    }

    // Sprint 42 dead modal dialogs removed (inline VideoBlock).

    if (showRecreateConfirm) {
        AlertDialog(
            onDismissRequest = { showRecreateConfirm = false },
            title = { Text(stringResource(R.string.recreate_confirm_title)) },
            text = { Text(stringResource(R.string.recreate_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showRecreateConfirm = false
                    showCreateVideoDialog = true
                }) {
                    Text(stringResource(R.string.action_recreate_video))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecreateConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showCreateVideoDialog) {
        var sfxEnabled by remember { mutableStateOf(keepSfx) }
        AlertDialog(
            onDismissRequest = { showCreateVideoDialog = false },
            title = { Text(stringResource(R.string.action_create_video)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.music_default))
                    OutlinedButton(onClick = { pickMusicLauncher.launch(arrayOf("audio/*")) }) {
                        Text(stringResource(R.string.action_pick_music))
                    }
                    musicPath?.let { Text(stringResource(R.string.music_chosen, it.substringAfterLast('/'))) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(sfxEnabled, onCheckedChange = { sfxEnabled = it })
                        Text(stringResource(R.string.video_sfx_toggle_full), Modifier.weight(1f))
                    }
                    // Sprint 63: no duration picker. The clip length is always what the app
                    // computes itself from the ride distance, photos and the encode budget.
                    Text(
                        text = stringResource(R.string.video_duration_auto_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    track?.let { viewModel.createVideo(it, sfxEnabled) }
                    showCreateVideoDialog = false
                }) { Text(stringResource(R.string.action_start_video)) }
            },
            dismissButton = { TextButton(onClick = { showCreateVideoDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }



    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteTrack()
                    onDone()
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun avgSpeed(track: Track): Float =
    if (track.duration > 0) track.distance / (track.duration / 1000f) else 0f

private fun maxSpeed(track: Track): Float =
    track.points.maxOfOrNull { it.speed } ?: 0f

@Composable
private fun DetailStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Sprint 69: "Поделиться / сохранить фото поездки" - on-device HQ bitmap card.
 * Two backdrops (OSM scheme like the live map / Esri satellite like the video),
 * stats block bottom-centre, translucent elevation chart top-right with the route
 * shining through. Save -> MediaStore gallery, share -> FileProvider ACTION_SEND.
 * No new SDKs; OOM-safe (one bitmap at a time, recycled after save/share).
 */
@Composable
private fun TripPhotoCard(
    track: Track,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(track.id) { mutableStateOf(false) }
    var styleMenu by remember(track.id) { mutableStateOf(false) }
    var saveNotice by remember(track.id) { mutableStateOf<String?>(null) }
    if (track.points.isEmpty()) return
    OutlinedButton(
        onClick = { styleMenu = true },
        modifier = modifier.fillMaxWidth(),
        enabled = !busy
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            stringResource(R.string.action_share_trip_photo) +
                if (busy) "…" else ""
        )
    }
    if (styleMenu) {
        AlertDialog(
            onDismissRequest = { if (!busy) styleMenu = false },
            title = { Text(stringResource(R.string.share_trip_photo_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            styleMenu = false
                            scope.launch {
                                busy = true
                                saveNotice = shareTripPhotoCard(
                                    context, track,
                                    com.alexander.routestory.utils.TripSnapshotRenderer.Style.SCHEMATIC
                                )
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.share_trip_photo_scheme)) }
                    Button(
                        onClick = {
                            styleMenu = false
                            scope.launch {
                                busy = true
                                saveNotice = shareTripPhotoCard(
                                    context, track,
                                    com.alexander.routestory.utils.TripSnapshotRenderer.Style.SATELLITE
                                )
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.share_trip_photo_satellite)) }
                    OutlinedButton(
                        onClick = { styleMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            },
            confirmButton = {}
        )
    }
    saveNotice?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Renders the HQ card via the Hilt graph, saves it to the gallery (MediaStore) and
 * opens the system share sheet (FileProvider). Returns a short status line for the UI.
 * One bitmap at a time, recycled right after use (OOM care).
 */
private suspend fun shareTripPhotoCard(
    context: android.content.Context,
    track: Track,
    style: com.alexander.routestory.utils.TripSnapshotRenderer.Style
): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val entry = dagger.hilt.EntryPoints.get(
        context.applicationContext,
        TripSnapshotEntryPoint::class.java
    )
    val bitmap = entry.tripSnapshotRenderer().render(track, style)
        as? android.graphics.Bitmap
    if (bitmap == null) {
        return@withContext context.getString(R.string.share_trip_photo_error)
    }
    try {
        entry.tripPhotoShareHelper().saveAndShare(bitmap, track)
        context.getString(R.string.share_trip_photo_saved)
    } catch (_: Exception) {
        context.getString(R.string.share_trip_photo_error)
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** Hilt entry point: the card is rendered from a @Composable without @Inject access. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TripSnapshotEntryPoint {
    fun tripSnapshotRenderer(): com.alexander.routestory.utils.TripSnapshotRenderer
    fun tripPhotoShareHelper(): com.alexander.routestory.utils.TripPhotoShareHelper
}

@Composable
private fun rememberPhotoBitmap(path: String, maxDim: Int): Bitmap? {
    return remember(path, maxDim) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}

@Composable
private fun PhotoThumb(photo: TrackPhoto, onClick: () -> Unit) {
    val bitmap = rememberPhotoBitmap(photo.uri, 128)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text("?")
        }
    }
}
@Composable
private fun VideoBlock(
    trackId: Long,
    trackName: String,
    modifier: Modifier = Modifier,
    onSave: (File) -> Unit = {},
    viewModel: TrackDetailViewModel = hiltViewModel()
) {
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ownState = videoState.takeIf { stateTrackId(it) == trackId }
    val preparing = ownState as? VideoExportState.Preparing
    val running = ownState as? VideoExportState.Running
    val doneFile = (ownState as? VideoExportState.Done)?.file
    val archived = remember(trackId, videoState::class) { viewModel.archivedVideo(trackId) }
    var autoplayConsumed by rememberSaveable(trackId) { mutableStateOf<String?>(null) }
    // Sprint 47: autoplay is allowed only for an export that STARTED while this very
    // screen was showing it; opening a saved ride from History/notification never
    // starts playback by itself.
    var exportStartedHere by rememberSaveable(trackId) { mutableStateOf(false) }
    LaunchedEffect(preparing, running) {
        if (preparing != null || running != null) exportStartedHere = true
    }
    // The fresh export wins while its state is present; afterwards the archive.
    val file = doneFile?.takeIf { it.isFile && it.length() > 0L } ?: archived
    if (preparing == null && running == null && file == null) return
    // Sprint 64: no card behind the clip anymore. The player keeps the screen
    // background and is sized to the whole screen width (the detail column adds a
    // 16.dp inset, so the clip is centred and bleeds past it to both edges).
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (preparing == null && running == null && file != null) {
            val freshAutoPlay = exportStartedHere && doneFile != null && file.absolutePath != autoplayConsumed
            InlineVideoPlayer(
                file = file,
                modifier = Modifier.width(screenWidthDp.dp),
                autoPlay = freshAutoPlay,
                onAutoplayConsumed = { autoplayConsumed = file.absolutePath },
                onShare = { shareVideo(file, context) },
                onSave = { onSave(file) }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (preparing != null) {
                    Text(stringResource(R.string.video_preparing), style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val r = running
                if (r != null) {
                    Text(
                        text = stringResource(R.string.video_generating),
                        style = MaterialTheme.typography.titleSmall
                    )
                    LinearProgressIndicator(
                        progress = { r.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = (r.progress * 100).toInt().toString() + " %")
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.cancelVideo() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineVideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    autoPlay: Boolean,
    onAutoplayConsumed: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    // Sprint 64: plain VideoView with one compact media bar laid over the BOTTOM of
    // the frame: play/pause on the left, the wide seek line in the middle, share and
    // save on the right. No stop button, no fullscreen dialog (Sprint 64 removed both).
    var videoView by remember(file) { mutableStateOf<VideoView?>(null) }
    var durationMs by remember(file) { mutableStateOf(0) }
    var positionMs by remember(file) { mutableStateOf(0) }
    var dragging by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var isPlaying by remember(file) { mutableStateOf(false) }

    LaunchedEffect(videoView) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val v = videoView ?: break
            if (!dragging) {
                positionMs = v.currentPosition
                if (v.duration > 0) durationMs = v.duration
                isPlaying = v.isPlaying
            }
        }
    }
    LaunchedEffect(failed) {
        if (failed) videoView = null
    }

    if (!failed) {
        Box(modifier = modifier.height(220.dp)) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            setOnCompletionListener { positionMs = durationMs; isPlaying = false }
                            durationMs = mp.duration
                            if (autoPlay) {
                                start(); isPlaying = true
                                positionMs = 0; onAutoplayConsumed()
                            }
                        }
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.w("MapSnapshot", "inline video error what=$what extra=$extra")
                            failed = true; true
                        }
                        setVideoURI(android.net.Uri.fromFile(file))
                        setOnClickListener {
                            if (isPlaying) { pause(); isPlaying = false }
                            else { if (duration > 0 && currentPosition >= duration) seekTo(0); start(); isPlaying = true }
                        }
                        videoView = this
                    }
                },
                onRelease = { videoView = null; it.stopPlayback() },
                modifier = Modifier.fillMaxSize()
            )
            VideoControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onSeek = { dragging = true; positionMs = it },
                onSeekFinished = { videoView?.seekTo(positionMs); dragging = false },
                onPlayPause = {
                    val v = videoView
                    if (v != null) {
                        if (v.isPlaying) { v.pause(); isPlaying = false } else { v.start(); isPlaying = true }
                    }
                },
                onShare = onShare,
                onSave = onSave,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun VideoControls(
    positionMs: Int, durationMs: Int, isPlaying: Boolean,
    onSeek: (Int) -> Unit, onSeekFinished: () -> Unit,
    onPlayPause: () -> Unit, onShare: () -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Sprint 64: one compact media bar drawn ON the bottom of the video frame.
    // Sprint 69: play pinned left, share+save pinned right, timeline weight(1f) between
    // them with gaps. Tap on the timeline seeks (fraction of width), drag as before.
    // Plain clickable Boxes replace IconButton, whose built-in 48 dp minimum touch
    // target would otherwise override the weights and squeeze the seek bar.
    var width by remember { mutableStateOf(1) }
    fun seekFromX(x: Float) {
        val target = (x / width.toFloat() * durationMs).toInt()
            .coerceIn(0, durationMs.coerceAtLeast(0))
        onSeek(target)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))
                )
            )
            .padding(start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .fillMaxHeight()
                .clickable { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.action_pause else R.string.video_action_play
                ),
                tint = Color.White,
                modifier = Modifier.size(27.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 6.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(durationMs, width) {
                        detectTapGestures(
                            onTap = {
                                seekFromX(it.x)
                                onSeekFinished()
                            }
                        )
                    }
                    .pointerInput(durationMs, width) {
                        detectHorizontalDragGestures(
                            onDragStart = { seekFromX(it.x) },
                            onHorizontalDrag = { change, _ -> seekFromX(change.position.x) },
                            onDragEnd = { onSeekFinished() }
                        )
                    }
            ) {
                val y = size.height / 2f
                val p = (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.8f)
                drawLine(Color.White, Offset(0f, y), Offset(size.width * p, y), strokeWidth = 2.8f)
                drawCircle(Color.White, 5f, Offset(size.width * p, y))
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .fillMaxHeight()
                .clickable { onShare() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.action_share),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .fillMaxHeight()
                .clickable { onSave() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Save,
                contentDescription = stringResource(R.string.action_save),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun shareVideo(file: File, context: Context) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
