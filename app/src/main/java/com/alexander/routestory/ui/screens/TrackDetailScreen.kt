package com.alexander.routestory.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.Track
import com.alexander.routestory.utils.GeoUtils
import com.alexander.routestory.utils.GpxExporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(onBack: () -> Unit, viewModel: TrackDetailViewModel = hiltViewModel()) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RouteMapPlaceholder(
                    points = current.points,
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
                Button(
                    onClick = { exportLauncher.launch(GpxExporter.suggestFileName(current)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.export_gpx))
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