package com.alexander.routestory.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackingState
import com.alexander.routestory.service.TrackSaveOutcome
import com.alexander.routestory.utils.GeoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    onTrackSaved: (Long) -> Unit = {},
    viewModel: TrackingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasPermissions by remember { mutableStateOf(false) }
    var gpsEnabled by remember { mutableStateOf(true) }
    var locateTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        gpsEnabled = isGpsEnabled(context)
    }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) viewModel.requestInitialFix()
    }

    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) permissionLauncher.launch(permissions)
        else viewModel.requestInitialFix()
    }

    // Permissions and the GPS switch can change while the app is in the background.
    DisposableEffect(lifecycleOwner, permissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                gpsEnabled = isGpsEnabled(context)
                hasPermissions = permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.saveOutcome.collect { outcome ->
            when (outcome) {
                is TrackSaveOutcome.Saved -> {
                    viewModel.consumeSaveOutcome()
                    onTrackSaved(outcome.trackId)
                }
                TrackSaveOutcome.Discarded -> {
                    viewModel.consumeSaveOutcome()
                    snackbarHostState.showSnackbar(context.getString(R.string.track_not_saved))
                }
                null -> Unit
            }
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
            if (!gpsEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gps_disabled))
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }) {
                            Text(stringResource(R.string.action_open_settings))
                        }
                    }
                }
            }
            RouteMapPlaceholder(
                points = state.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                followLastPoint = true,
                currentPosition = state.lastFix,
                locateTick = locateTick,
                onLocate = {
                    locateTick++
                    viewModel.requestInitialFix(force = true)
                }
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
                TrackingState.IDLE -> {
                    // Recording needs a current fix: without it the first accepted point
                    // could be a wild teleport and the track would start with garbage.
                    Button(
                        onClick = {
                            if (hasPermissions) viewModel.start() else permissionLauncher.launch(permissions)
                        },
                        enabled = hasPermissions && state.lastFix != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (!hasPermissions) R.string.action_grant_location else R.string.action_start
                            )
                        )
                    }
                    if (hasPermissions && state.lastFix == null) {
                        Text(
                            text = stringResource(R.string.waiting_gps),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
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

/** Reads the system GPS switch without throwing when the provider is unavailable. */
private fun isGpsEnabled(context: Context): Boolean = try {
    (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        .isProviderEnabled(LocationManager.GPS_PROVIDER)
} catch (_: Exception) {
    false
}