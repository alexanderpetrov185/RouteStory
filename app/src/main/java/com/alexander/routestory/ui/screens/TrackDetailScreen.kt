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
import com.alexander.routestory.utils.GpxExporter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(onBack: () -> Unit, onOpenPro: () -> Unit = {}, viewModel: TrackDetailViewModel = hiltViewModel()) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val videoState by viewModel.videoState.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<TrackPhoto?>(null) }
    var showShiftDialog by remember { mutableStateOf(false) }

    val pickPhotosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addPhotos(uris) }

    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri -> uri?.let { viewModel.saveVideoTo(it) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { viewModel.exportGpx(it) } }

    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { ok ->
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.export_done else R.string.export_error)
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                current.photos.map { photo ->
                    photo.latitude?.let { lat ->
                        photo.longitude?.let { lon -> lat to lon }
                    } ?: GeoUtils.interpolateByTime(current.points, photo.timestamp)
                }.filterNotNull()
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
                    Text(current.name, style = MaterialTheme.typography.titleLarge)
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
                Button(
                    onClick = { exportLauncher.launch(GpxExporter.suggestFileName(current)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.export_gpx))
                }
                Button(
                    onClick = { viewModel.createVideo(current) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = videoState !is VideoExportState.Running
                ) {
                    Text(stringResource(R.string.action_create_video))
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
            }
        }
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
            AlertDialog(
                onDismissRequest = { viewModel.consumeVideoResult() },
                title = { Text(stringResource(R.string.video_done)) },
                text = {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(videoResult.file.absolutePath)
                                setOnCompletionListener { it.start() }
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
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
                    onBack()
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
private fun shareVideo(file: File, context: android.content.Context) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
