package com.alexander.routestory.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.utils.GeoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(viewModel: TrackingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasPermissions by remember { mutableStateOf(false) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasPermissions = results.values.all { it } }

    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) permissionLauncher.launch(permissions)
    }

    LaunchedEffect(Unit) {
        viewModel.trackSaved.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.track_saved))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tracking_title)) }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RouteMapPlaceholder(
                points = state.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(stringResource(R.string.stat_distance), GeoUtils.formatDistance(state.distanceMeters))
                StatItem(stringResource(R.string.stat_time), GeoUtils.formatDuration(state.durationMs))
                StatItem(stringResource(R.string.stat_speed), GeoUtils.formatSpeed(state.speedMps))
            }
            when (state.state) {
                TrackingState.IDLE -> Button(
                    onClick = {
                        if (hasPermissions) viewModel.start() else permissionLauncher.launch(permissions)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_start))
                }
                TrackingState.RECORDING -> {
                    OutlinedButton(onClick = viewModel::pause, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_pause))
                    }
                    Button(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
                TrackingState.PAUSED -> {
                    Button(onClick = viewModel::resume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_resume))
                    }
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}