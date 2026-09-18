package com.alexander.routestory.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var gpsEnabled by remember { mutableStateOf(isGpsEnabled(context)) }
    var locateTick by remember { mutableStateOf(0) }
    var gpsResolutionPending by remember { mutableStateOf(false) }

    fun refreshGpsState(fromProviderChange: Boolean = false) {
        gpsEnabled = isGpsEnabled(context)
        if (gpsEnabled && hasPermissions && (fromProviderChange || state.lastFix == null)) {
            viewModel.requestInitialFix(force = true)
        }
    }

    // SettingsClient resolution: "Turn on location?" dialog, no app restart needed.
    val gpsResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        gpsResolutionPending = false
        refreshGpsState(fromProviderChange = true)
    }

    fun requestEnableGps() {
        runCatching {
            val client = com.google.android.gms.location.LocationServices.getSettingsClient(context)
            val request = com.google.android.gms.location.LocationSettingsRequest.Builder()
                .addLocationRequest(
                    com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 2000L
                    ).build()
                )
                .setAlwaysShow(true)
                .build()
            client.checkLocationSettings(request)
                .addOnSuccessListener {
                    refreshGpsState(fromProviderChange = true)
                }
                .addOnFailureListener { e ->
                    val resolvable = e as? com.google.android.gms.common.api.ResolvableApiException
                    val sender = resolvable?.resolution?.intentSender
                    if (sender != null) {
                        gpsResolutionPending = true
                        gpsResolutionLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        )
                    }
                }
        }.onFailure {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            )
        }
    }

    // Live GPS provider changes: no restart needed after toggling GPS in settings.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION ||
                    intent?.action == LocationManager.MODE_CHANGED_ACTION
                ) {
                    refreshGpsState(fromProviderChange = true)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Camera: TakePicture writes into a FileProvider file prepared by the ViewModel.
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        viewModel.captureResult(ok)
        pendingUri = null
    }
    LaunchedEffect(Unit) {
        viewModel.photoErrors.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.photo_error))
        }
    }

    // A session killed by the OS leaves a draft on disk; ask whether to continue it.
    LaunchedEffect(Unit) { viewModel.checkDraft() }

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
                refreshGpsState(fromProviderChange = true)
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
                        OutlinedButton(onClick = { requestEnableGps() }) {
                            Text(
                                if (gpsResolutionPending) stringResource(R.string.waiting_gps)
                                else stringResource(R.string.action_enable_gps)
                            )
                        }
                    }
                }
            }
            RouteMapPlaceholder(
                points = state.points,
                photoPositions = photoPositionsOf(state.points, state.photos),
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
            val photoCounter = if (viewModel.photoLimit == Int.MAX_VALUE) "${state.photos.size}"
            else "${state.photos.size}/${viewModel.photoLimit}"
            val photoLabel = stringResource(R.string.photo_action) + " · " + photoCounter
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
                            text = stringResource(
                                if (!gpsEnabled) R.string.gps_disabled_short
                                else R.string.searching_gps
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
                TrackingState.RECORDING -> {
                    OutlinedButton(onClick = viewModel::pause, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_pause))
                    }
                    OutlinedButton(
                        onClick = {
                            if (state.capturePending) return@OutlinedButton
                            val uri = viewModel.preparePhoto()
                            if (uri != null) {
                                pendingUri = uri
                                cameraLauncher.launch(uri)
                            }
                        },
                        enabled = !state.capturePending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Text("  " + photoLabel)
                    }
                    Button(
                        onClick = viewModel::stop,
                        enabled = !state.capturePending && pendingUri == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
                TrackingState.PAUSED -> {
                    Button(onClick = viewModel::resume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_resume))
                    }
                    OutlinedButton(
                        onClick = {
                            if (state.capturePending) return@OutlinedButton
                            val uri = viewModel.preparePhoto()
                            if (uri != null) {
                                pendingUri = uri
                                cameraLauncher.launch(uri)
                            }
                        },
                        enabled = !state.capturePending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Text("  " + photoLabel)
                    }
                    OutlinedButton(
                        onClick = viewModel::stop,
                        enabled = !state.capturePending && pendingUri == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
        }
    }

    if (state.draftAvailable && state.state == TrackingState.IDLE) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.draft_found_title)) },
            text = { Text(stringResource(R.string.draft_found_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreDraft()
                    locateTick++
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardDraft) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Post-capture review: the systematic camera preview is black on many devices, so the
    // shot is reviewed in an in-app Material3 dialog instead.
    val previewPath by viewModel.photoPreview.collectAsStateWithLifecycle()
    previewPath?.let { path ->
        PostCaptureDialog(
            path = path,
            count = state.photos.size + 1,
            onAdd = { viewModel.finishPhoto(true) },
            onRetake = {
                val uri = viewModel.retakePhoto()
                if (uri != null) {
                    pendingUri = uri
                    cameraLauncher.launch(uri)
                }
            },
            onCancel = { viewModel.finishPhoto(false) }
        )
    }
}

@Composable
private fun PostCaptureDialog(
    path: String,
    count: Int,
    onAdd: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, 720) }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.photo_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }
                Text(stringResource(R.string.photo_preview_hint, count))
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRetake) { Text(stringResource(R.string.action_retake)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

/** Down-sampled decode: a full-size camera frame would be tens of MB in the dialog. */
private fun decodeSampled(path: String, maxSize: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
    android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    )
}.getOrNull()

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