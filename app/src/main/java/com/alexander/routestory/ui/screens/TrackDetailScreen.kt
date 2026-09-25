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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
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
                        GeoUtils.formatDate(current.startTime),
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
        // Sprint 49: explicit marker MOVE time; null = "let the app decide" (auto).
        var targetMapSec by remember { mutableStateOf<Int?>(null) }
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
                    Text(
                        text = stringResource(R.string.video_duration_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    val durationOptions = listOf(
                        stringResource(R.string.video_duration_auto) to null,
                        stringResource(R.string.video_duration_30) to 30,
                        stringResource(R.string.video_duration_60) to 60,
                        stringResource(R.string.video_duration_120) to 120
                    )
                    durationOptions.forEach { (label, seconds) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { targetMapSec = seconds },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = targetMapSec == seconds,
                                onClick = { targetMapSec = seconds }
                            )
                            Text(label)
                        }
                    }
                    Text(
                        text = stringResource(R.string.video_duration_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    targetMapSec?.let { mapSec ->
                        val photoCount = minOf(track?.photos?.size ?: 0, if (isPro) 12 else 10)
                        val hold = if (photoCount == 0) 0f else
                            (maxOf(60f, (mapSec + 4) * 0.5f) / photoCount).coerceIn(1f, 5f)
                        val extra = 15 + (hold * photoCount).toInt()
                        Text(
                            text = stringResource(
                                R.string.video_duration_estimate,
                                mapSec,
                                mapSec + extra,
                                extra
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    track?.let {
                        viewModel.createVideo(it, sfxEnabled, targetMapSec)
                    }
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            if (preparing == null && running == null && file != null) {
                val freshAutoPlay = exportStartedHere && doneFile != null && file.absolutePath != autoplayConsumed
                InlineVideoPlayer(
                    file = file,
                    modifier = Modifier.fillMaxWidth(),
                    autoPlay = freshAutoPlay,
                    onAutoplayConsumed = { autoplayConsumed = file.absolutePath }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        shareVideo(file, context)
                    }) {
                        Text(stringResource(R.string.action_share))
                    }
                    TextButton(onClick = {
                        onSave(file)
                    }) {
                        Text(stringResource(R.string.action_save))
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
    onAutoplayConsumed: () -> Unit
) {
    // Sprint 42: seek slider + fullscreen dialog, no new libraries (plain VideoView).
    // Sprint 43: compact timeline + icon transport controls (play/pause/stop/fullscreen).
    var videoView by remember(file) { mutableStateOf<VideoView?>(null) }
    var durationMs by remember(file) { mutableStateOf(0) }
    var positionMs by remember(file) { mutableStateOf(0) }
    var dragging by remember(file) { mutableStateOf(false) }
    var fullscreenAt by remember(file) { mutableStateOf<Int?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    // Fullscreen handoff: inline pauses/releases first, and these two remember where
    // the single remaining player stopped so the inline view can resume exactly there.
    var resumePos by remember(file) { mutableStateOf(0) }
    var resumePlay by remember(file) { mutableStateOf(false) }

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
        Box(modifier = modifier.fillMaxWidth().height(220.dp)) {
            // Unmounted while the fullscreen dialog is open (onRelease -> stopPlayback),
            // so inline and fullscreen can never play at the same time.
            if (fullscreenAt == null) AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            setOnCompletionListener { positionMs = durationMs; isPlaying = false }
                            durationMs = mp.duration
                            // Coming back from fullscreen: continue at the saved position
                            // and start only when it was playing there.
                            val resume = resumePos
                            if (resume > 0) { seekTo(resume); positionMs = resume }
                            resumePos = 0
                            if (autoPlay || resumePlay) {
                                start(); isPlaying = true
                                if (autoPlay) { positionMs = 0; onAutoplayConsumed() }
                            }
                            resumePlay = false
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
                onStop = { videoView?.let { it.pause(); it.seekTo(0) }; positionMs = 0; isPlaying = false },
                onFullscreen = {
                    // Single audio source: pause + release the inline view first; the
                    // fullscreen player takes over from this exact position.
                    resumePos = videoView?.currentPosition ?: positionMs
                    resumePlay = isPlaying
                    videoView?.pause()
                    isPlaying = false
                    fullscreenAt = resumePos
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
    val at = fullscreenAt
    if (at != null) {
        // resumePos/resumePlay follow the fullscreen player (onState), so any close
        // path leaves the inline view right where the user stopped it.
        val closeFullscreen = { fullscreenAt = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = closeFullscreen) {
            FullscreenPlayer(
                file = file,
                startAt = at,
                onState = { pos, playing -> resumePos = pos; resumePlay = playing },
                onClose = closeFullscreen
            )
        }
    }
}

@Composable
private fun FullscreenPlayer(
    file: File,
    startAt: Int,
    onState: (positionMs: Int, playing: Boolean) -> Unit,
    onClose: () -> Unit
) {
    // Sprint 42: same VideoView/seek pattern sized to the dialog.
    // Sprint 43: icon transport controls + compact timeline; the "Закрыть" label is
    // replaced by a Close icon in the top-right corner.
    var fsView by remember(file) { mutableStateOf<VideoView?>(null) }
    var fsDuration by remember(file) { mutableStateOf(0) }
    var fsPosition by remember(file, startAt) { mutableStateOf(startAt) }
    var fsDragging by remember(file) { mutableStateOf(false) }
    var fsPlaying by remember(file) { mutableStateOf(false) }

    LaunchedEffect(fsView) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val v = fsView ?: break
            if (!fsDragging) {
                fsPosition = v.currentPosition
                if (v.duration > 0) fsDuration = v.duration
                fsPlaying = v.isPlaying
                onState(fsPosition, fsPlaying)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            fsDuration = mp.duration
                            seekTo(startAt)
                            start()
                            fsPlaying = true
                            onState(startAt, true)
                        }
                        setOnCompletionListener {
                            fsPosition = duration
                            fsPlaying = false
                        }
                        setOnErrorListener { _, _, _ -> true }
                        setVideoURI(android.net.Uri.fromFile(file))
                        setOnClickListener {
                            if (isPlaying) { pause(); fsPlaying = false }
                            else { start(); fsPlaying = true }
                        }
                        fsView = this
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            // Fullscreen controls remain visible; the old slider is intentionally removed.
            VideoControls(
                fsPosition, fsDuration, fsPlaying,
                onSeek = { fsDragging = true; fsPosition = it },
                onSeekFinished = { fsView?.seekTo(fsPosition); fsDragging = false },
                onPlayPause = { fsView?.let { if (fsPlaying) { it.pause(); fsPlaying = false } else { it.start(); fsPlaying = true } } },
                onStop = { fsView?.let { it.pause(); it.seekTo(0) }; fsPosition = 0; fsPlaying = false },
                onFullscreen = onClose
            )
        }
    }
}

@Composable
private fun VideoControls(
    positionMs: Int, durationMs: Int, isPlaying: Boolean,
    onSeek: (Int) -> Unit, onSeekFinished: () -> Unit,
    onPlayPause: () -> Unit, onStop: () -> Unit, onFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var width by remember { mutableStateOf(1) }
    Box(modifier.fillMaxWidth().height(48.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        Canvas(Modifier.fillMaxWidth().height(18.dp).align(Alignment.BottomCenter).padding(horizontal = 8.dp)
            .pointerInput(durationMs, width) {
                detectHorizontalDragGestures(
                    onDragStart = { onSeek((it.x / width * durationMs).toInt()) },
                    onHorizontalDrag = { change, _ -> onSeek((change.position.x / width * durationMs).toInt()) },
                    onDragEnd = { onSeekFinished() }
                )
            }) {
            val y = size.height / 2f
            val p = (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            drawLine(Color.White.copy(alpha = .55f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            drawLine(Color.White, Offset(0f, y), Offset(size.width * p, y), strokeWidth = 2f)
            drawCircle(Color.White, 3.5f, Offset(size.width * p, y))
        }
        Row(Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, null, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onFullscreen) { Icon(Icons.Filled.FitScreen, null, modifier = Modifier.size(20.dp)) }
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
