package com.alexander.routestory.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.alexander.routestory.domain.PlannedRoute
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.utils.GeoUtils
import kotlinx.coroutines.launch

/**
 * Sprint 61: planned-route builder. Waypoints come from a short map hold, the road
 * geometry from OSRM, and the "me" dot only from a real GPS fix --- so runtime permissions and
 * the GPS switch are part of the screen state, exactly like on the tracking screen.
 *
 * Sprint 65:
 *  - "Начать маршрут" hands over an EPHEMERAL plan ([PlannedRoute] with id 0) and starts the
 *    recording right away; the route is written to Room only by "Сохранить";
 *  - points are edited on the map: tap a marker -> delete (with confirm), long-press a
 *    marker and drag -> move it.
 */
@Composable
fun RouteBuilderScreen(
    onBack: () -> Unit = {},
    onStart: (PlannedRoute) -> Unit = {},
    viewModel: RouteBuilderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var locateTick by remember { mutableStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(viewModel.hasLocationPermission(context)) }
    var gpsEnabled by remember { mutableStateOf(isBuilderGpsEnabled(context)) }
    var gpsResolutionPending by remember { mutableStateOf(false) }
    // Sprint 65: marker tapped on the map -> confirm dialog for the delete.
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshGpsState(fromProviderChange: Boolean = false) {
        gpsEnabled = isBuilderGpsEnabled(context)
        if (gpsEnabled && hasPermissions && (fromProviderChange || state.lastFix == null)) {
            viewModel.onPermissionsGranted()
            // Sprint 62: the camera may only jump to a position that really exists - a tick
            // before the first fix used to center the map on the tile fallback instead.
            if (viewModel.uiState.value.lastFix != null) locateTick++
        }
    }

    val gpsResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        gpsResolutionPending = false
        refreshGpsState(fromProviderChange = true)
    }

    // SettingsClient resolution: the system "Turn on location?" dialog, no restart needed.
    fun requestEnableGps() {
        runCatching {
            val client = com.google.android.gms.location.LocationServices.getSettingsClient(context)
            val request = com.google.android.gms.location.LocationSettingsRequest.Builder()
                .addLocationRequest(
                    com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 2_000L
                    ).build()
                )
                .setAlwaysShow(true)
                .build()
            gpsResolutionPending = true
            client.checkLocationSettings(request)
                .addOnSuccessListener {
                    gpsResolutionPending = false
                    refreshGpsState(fromProviderChange = true)
                }
                .addOnFailureListener { e ->
                    val sender =
                        (e as? com.google.android.gms.common.api.ResolvableApiException)?.resolution
                    if (sender != null) {
                        gpsResolutionLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        gpsResolutionPending = false
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
        }.onFailure {
            gpsResolutionPending = false
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = viewModel.hasLocationPermission(context)
        if (hasPermissions) refreshGpsState(fromProviderChange = true)
    }

    // Returning from the permission dialog or system settings: re-read the real state.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = builderPermissions().all {
                    ContextCompat.checkSelfPermission(context, it) ==
                        PackageManager.PERMISSION_GRANTED
                }
                refreshGpsState(fromProviderChange = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // GPS toggled from the shade or settings must take effect without an app restart.
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

    // Ask for the permission on the first visit; GPS state is read on every resume.
    LaunchedEffect(Unit) {
        hasPermissions = viewModel.hasLocationPermission(context)
        gpsEnabled = isBuilderGpsEnabled(context)
        if (hasPermissions) refreshGpsState(fromProviderChange = true)
        else permissionLauncher.launch(builderPermissions())
    }

    RouteBuilderContent(
        state = state,
        hasPermissions = hasPermissions,
        gpsEnabled = gpsEnabled,
        gpsResolutionPending = gpsResolutionPending,
        locateTick = locateTick,
        confirmExit = confirmExit,
        onConfirmExitChange = { confirmExit = it },
    // Sprint 65: the delete-confirm state lives in the stateful wrapper (it survives a
    // recomposition of the content), and is handed down as plain data + callbacks.
    pendingDeleteIndex = pendingDeleteIndex,
    onRequestDeletePoint = { pendingDeleteIndex = it },
    onDeletePointConfirmed = { index ->
        pendingDeleteIndex = null
        viewModel.removeAt(index)
    },
    onDeletePointDismissed = { pendingDeleteIndex = null },
        onLocateTick = { locateTick++ },
        onRequestPermissions = { permissionLauncher.launch(builderPermissions()) },
        onRequestEnableGps = { requestEnableGps() },
        onBack = onBack,
        onStart = onStart,
        viewModel = viewModel
    )
}

/** Runtime permissions the builder asks for (notifications on 13+ for the recording service). */
private fun builderPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

/** Reads the system GPS switch without throwing when the provider is unavailable. */
internal fun isBuilderGpsEnabled(context: Context): Boolean = try {
    (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        .isProviderEnabled(LocationManager.GPS_PROVIDER)
} catch (_: Exception) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteBuilderContent(
    state: RouteBuilderUiState,
    hasPermissions: Boolean,
    gpsEnabled: Boolean,
    gpsResolutionPending: Boolean,
    locateTick: Int,
    confirmExit: Boolean,
    onConfirmExitChange: (Boolean) -> Unit,
    onLocateTick: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestEnableGps: () -> Unit,
    onBack: () -> Unit,
    onStart: (PlannedRoute) -> Unit,
    /** Sprint 65: marker tapped on the map, waiting for the delete confirmation. */
    pendingDeleteIndex: Int?,
    onRequestDeletePoint: (Int) -> Unit,
    onDeletePointConfirmed: (Int) -> Unit,
    onDeletePointDismissed: () -> Unit,
    viewModel: RouteBuilderViewModel
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Only a real fix may draw the "me" dot and centre the map; before the first fix the map
    // stays empty and says so - it must never open on a placeholder city.
    val mePosition: Pair<Double, Double>? = state.lastFix
    val statusText = when {
        !hasPermissions || !gpsEnabled -> null
        mePosition == null -> stringResource(R.string.searching_gps)
        else -> null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.route_builder_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.waypoints.isNotEmpty() && !state.saved) onConfirmExitChange(true)
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasPermissions) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.route_location_needed))
                        Button(onClick = onRequestPermissions) {
                            Text(stringResource(R.string.action_grant_location))
                        }
                    }
                }
            }
            if (hasPermissions && !gpsEnabled) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.gps_disabled))
                        OutlinedButton(onClick = onRequestEnableGps) {
                            Text(
                                if (gpsResolutionPending) stringResource(R.string.waiting_gps)
                                else stringResource(R.string.action_enable_gps)
                            )
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.route_longpress_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Text(
                stringResource(
                    R.string.route_points_count,
                    state.waypoints.size,
                    RouteBuilderViewModel.MAX_WAYPOINTS
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            statusText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            RouteMapPlaceholder(
                // The planned road line is the main content of this screen, so it is drawn
                // with the normal route style and the camera fits it automatically.
                points = state.roadGeometry.mapIndexed { i, p ->
                    TrackPoint(p.latitude, p.longitude, 0.0, 0f, 0f, i.toLong())
                },
                waypointMarkers = state.waypoints,
                currentPosition = mePosition,
                locateTick = locateTick,
                // Sprint 62: no tile fallback for the camera - the map starts empty ("Ищем GPS…")
                // and lands on the real fix, never on a stand-in city.
                initialCentre = null,
                startMarkerIndex = if (state.waypoints.isNotEmpty()) 0 else -1,
                warnNoFixOnLocate = false,
                onLocate = {
                    onLocateTick()
                    if (!hasPermissions) onRequestPermissions()
                    else if (!gpsEnabled) onRequestEnableGps()
                    else viewModel.requestFix(force = true)
                },
                // Waypoints are created only by a short map hold, never by a short tap.
                onMapLongPress = viewModel::addPoint,
                // Sprint 65: existing markers are edited in place. A press that lands on a
                // marker never adds a point, so the two gestures cannot fight each other.
                onWaypointTap = onRequestDeletePoint,
                onWaypointMove = viewModel::movePoint,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            // Sprint 62: a failed OSRM attempt says so right under the map with a one-tap retry,
            // instead of only flashing a snackbar nobody sees.
            if (state.routingError) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.route_routing_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = viewModel::retryRoad) {
                            Text(stringResource(R.string.route_retry))
                        }
                    }
                }
            }
            BuilderActions(
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onStart = onStart,
                snackbarHostState = snackbarHostState
            )
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { onConfirmExitChange(false) },
            title = { Text(stringResource(R.string.route_exit_title)) },
            text = { Text(stringResource(R.string.route_exit_text)) },
            confirmButton = {
                TextButton(onClick = { onConfirmExitChange(false); onBack() }) {
                    Text(stringResource(R.string.route_exit_without_saving))
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmExitChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    // Sprint 65: a tap on a marker asks before deleting it - the start point (index 0) is
    // editable too, and deleting the last one returns the builder to the "empty" state where
    // waypoint #1 may be auto-placed on the fix again.
    pendingDeleteIndex?.let { index ->
        AlertDialog(
            onDismissRequest = onDeletePointDismissed,
            title = { Text(stringResource(R.string.route_delete_point_title)) },
            text = {
                Text(stringResource(R.string.route_delete_point_text, index + 1))
            },
            confirmButton = {
                TextButton(onClick = { onDeletePointConfirmed(index) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDeletePointDismissed) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    LaunchedEffect(state.errorTick) {
        if (state.errorTick > 0) {
            state.error?.let { snackbarHostState.showSnackbar(it) }
        }
    }
}

/** Distance line + the point-editing / save / start controls. */
@Composable
private fun BuilderActions(
    state: RouteBuilderUiState,
    viewModel: RouteBuilderViewModel,
    onBack: () -> Unit,
    onStart: (PlannedRoute) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (state.roadGeometry.isNotEmpty()) {
        Text(
            stringResource(R.string.route_distance, GeoUtils.formatDistance(state.distanceMeters)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = viewModel::removeLast,
            enabled = state.waypoints.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.route_undo_point)) }
        OutlinedButton(
            onClick = viewModel::clearPoints,
            enabled = state.waypoints.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.route_clear_points)) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Sprint 65: "Начать маршрут" NO LONGER saves. It hands an in-memory plan to the
        // tracking screen and starts the recording there; the route only reaches Room
        // (and "Сохранённые") through the explicit "Сохранить маршрут" button below.
        Button(
            onClick = {
                val plan = viewModel.buildEphemeralPlan()
                if (plan != null) {
                    onStart(plan)
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.route_routing_failed)
                        )
                    }
                }
            },
            enabled = state.roadGeometry.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.route_start)) }
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                if (viewModel.save() != null) onBack()
                else snackbarHostState.showSnackbar(context.getString(R.string.route_routing_failed))
            }
        },
        enabled = state.roadGeometry.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) { Text(stringResource(R.string.route_save)) }
}


