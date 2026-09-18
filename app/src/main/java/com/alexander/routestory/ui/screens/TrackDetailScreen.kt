package com.alexander.routestory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<TrackPhoto?>(null) }
    var showShiftDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // Sprint 22: the ride screen is view-only by default; editing (photos, re-rendering
    // the video) is an explicit action, gated to Pro.
    var editMode by remember { mutableStateOf(false) }

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri -> uri?.let { viewModel.saveVideoTo(it) } }

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
                CircularProgressIndicator()
            }
        } else {
            val photoPositions = remember(current.photos, current.points) {
                photoPositionsOf(current.points, current.photos)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RouteMapPlaceholder(
                    points = current.points,
                    photoPositions = photoPositions,
                    onPhotoTap = { index -> selectedPhoto = current.photos.getOrNull(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
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
                        if (isSummary) {
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
                Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.photos) { photo ->
                        PhotoThumb(photo = photo, onClick = { selectedPhoto = photo })
                    }
                }
                // Sprint 23: creating a video is a core action for Free users too — it is
                // always visible, not hidden behind the Pro-only edit mode.
                Button(
                    onClick = { viewModel.createVideo(current) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = videoState !is VideoExportState.Running
                ) {
                    Text(stringResource(R.string.action_create_video))
                }
                if (editMode) {
                    OutlinedButton(
                        onClick = {
                            pickPhotosLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_add_photo))
                    }
                } else {
                    // Pro-only: adding photos / re-rendering is the edit mode; for Free
                    // users the button leads to the Pro screen instead of hiding video.
                    OutlinedButton(
                        onClick = {
                            if (isPro) editMode = true else onOpenPro()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isPro) stringResource(R.string.action_edit)
                            else stringResource(R.string.action_edit) + " (Pro)"
                        )
                    }
                }
                // Watching an already rendered video is free for everyone.
                val archivedVideo = remember(current.id, videoState) {
                    viewModel.archivedVideo(current.id)
                }
                if (archivedVideo != null && videoState !is VideoExportState.Done) {
                    var showVideo by remember(current.id) { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showVideo = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_watch_video))
                    }
                    if (showVideo) {
                        AlertDialog(
                            onDismissRequest = { showVideo = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            title = { Text(current.name) },
                            text = {
                                SavedVideoPlayer(file = archivedVideo)
                            },
                            confirmButton = {
                                TextButton(onClick = { showVideo = false }) {
                                    Text(stringResource(R.string.action_close))
                                }
                            }
                        )
                    }
                }
                if (!isPro) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPro)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.pro_banner),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
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
                TextButton(onClick = { showShiftDialog = true }) {
                    Text(stringResource(R.string.action_shift_time))
                }
            }
        )
    }

    if (showShiftDialog) {
        var minutesText by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showShiftDialog = false },
            title = { Text(stringResource(R.string.action_shift_time)) },
            text = {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it },
                    label = { Text(stringResource(R.string.shift_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = selectedPhoto
                    val minutes = minutesText.toLongOrNull()
                    if (target != null && minutes != null && minutes != 0L) {
                        viewModel.shiftPhotoTime(target, minutes)
                    }
                    showShiftDialog = false
                    selectedPhoto = null
                }) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showShiftDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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

    when (val videoResult = videoState) {
        is VideoExportState.Running -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.video_generating)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { videoResult.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(text = (videoResult.progress * 100).toInt().toString() + " %")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelVideo() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        is VideoExportState.Done -> {
            var previewFailed by remember(videoResult.file) {
                mutableStateOf(!videoResult.file.isFile || videoResult.file.length() == 0L)
            }
            var previewPrepared by remember(videoResult.file) { mutableStateOf(false) }
            LaunchedEffect(videoResult.file) {
                kotlinx.coroutines.delay(15_000)
                if (!previewPrepared) previewFailed = true
            }
            LaunchedEffect(previewFailed) {
                if (previewFailed) {
                    screenScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.video_error))
                    }
                    viewModel.consumeVideoResult()
                }
            }
            var previewEnded by remember(videoResult.file) { mutableStateOf(false) }
            val videoViewRef = remember(videoResult.file) { mutableStateOf<VideoView?>(null) }
            if (!previewFailed) AlertDialog(
                onDismissRequest = { viewModel.consumeVideoResult() },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(stringResource(R.string.video_done)) },
                text = {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    videoViewRef.value = this
                                    // Local app file plays directly; start() only after the
                                    // surface is prepared (calling it too early is the usual
                                    // reason a preview stays black).
                                    setOnPreparedListener { mp ->
                                        previewPrepared = true
                                        // Sprint 24: play once; "watch again" restarts.
                                        mp.isLooping = false
                                        setOnCompletionListener { previewEnded = true }
                                        start()
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        android.util.Log.w("MapSnapshot", "preview error what=$what extra=$extra")
                                        previewFailed = true
                                        true
                                    }
                                    setVideoURI(android.net.Uri.fromFile(videoResult.file))
                                    // Tap toggles play/pause.
                                    setOnClickListener {
                                        if (isPlaying) pause() else start()
                                    }
                                }
                            },
                            onReset = null,
                            onRelease = { it.stopPlayback() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        saveVideoLauncher.launch("routestory_" + System.currentTimeMillis() + ".mp4")
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    Row {
                        if (previewEnded) {
                            TextButton(onClick = {
                                previewEnded = false
                                videoViewRef.value?.let { it.seekTo(0); it.start() }
                            }) {
                                Text(stringResource(R.string.action_watch_again))
                            }
                        }
                        TextButton(onClick = { shareVideo(videoResult.file, context) }) {
                            Text(stringResource(R.string.action_share))
                        }
                        TextButton(onClick = { viewModel.consumeVideoResult() }) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            )
        }
        else -> Unit
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
private fun SavedVideoPlayer(file: File) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    // Sprint 24: no looping anywhere; a tap restarts after the end.
                    mp.isLooping = false
                    start()
                }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.w("MapSnapshot", "saved video error what=$what extra=$extra")
                    true
                }
                setVideoURI(android.net.Uri.fromFile(file))
                setOnClickListener {
                    if (isPlaying) pause() else {
                        if (currentPosition >= duration && duration > 0) seekTo(0)
                        start()
                    }
                }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )
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
