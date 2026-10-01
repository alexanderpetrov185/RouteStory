package com.alexander.routestory.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexander.routestory.R
import com.alexander.routestory.data.PlannedRouteRepository
import com.alexander.routestory.domain.PlannedRoute
import com.alexander.routestory.domain.PlannedRoutePoint
import com.alexander.routestory.service.RouteRoutingClient
import com.alexander.routestory.utils.LocationFixProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouteBuilderUiState(
    val waypoints: List<Pair<Double, Double>> = emptyList(),
    val roadGeometry: List<PlannedRoutePoint> = emptyList(),
    val distanceMeters: Float = 0f,
    val lastFix: Pair<Double, Double>? = null,
    val fixPending: Boolean = false,
    /** True when the last OSRM attempt failed: the UI shows the message + "Retry". */
    val routingError: Boolean = false,
    val error: String? = null,
    val errorTick: Int = 0,
    val saved: Boolean = false
)
@HiltViewModel
class RouteBuilderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routingClient: RouteRoutingClient,
    private val plannedRouteRepository: PlannedRouteRepository,
    private val fixProvider: LocationFixProvider
) : ViewModel() {
    private val internalState = MutableStateFlow(RouteBuilderUiState())
    val uiState: StateFlow<RouteBuilderUiState> = internalState.asStateFlow()
    private var roadJob: Job? = null
    private var fixJob: Job? = null

    /**
     * Sprint 62: the first real fix becomes waypoint #1 (the red "start" marker) automatically,
     * so the user only has to long-press for the rest of the chain. Once the user has edited the
     * points himself (or cleared them) the auto start must never come back.
     */
    private var startAutoPlaced = false

    fun hasLocationPermission(ctx: Context = context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionsGranted() = requestFix(force = false)
    fun refreshFixIfNeeded() = requestFix(force = false)

    fun requestFix(force: Boolean = false) {
        if (!hasLocationPermission()) return
        if (!force && internalState.value.lastFix != null) return
        if (fixJob?.isActive == true && !force) return
        fixJob?.cancel()
        fixJob = viewModelScope.launch {
            internalState.value = internalState.value.copy(fixPending = true)
            val fix = runCatching { fixProvider.requestFix(context, force) }.getOrNull()
            val cur = internalState.value
            val next = cur.copy(lastFix = fix ?: cur.lastFix, fixPending = false)
            internalState.value = if (fix != null && !startAutoPlaced && next.waypoints.isEmpty()) {
                // Sprint 62: waypoint #1 = where the user actually is (red start marker).
                startAutoPlaced = true
                next.copy(waypoints = listOf(fix))
            } else {
                next
            }
        }
    }

    fun addPoint(lat: Double, lon: Double) {
        startAutoPlaced = true
        val cur = internalState.value
        if (cur.waypoints.size >= MAX_WAYPOINTS) {
            internalState.value = cur.copy(
                error = context.getString(R.string.route_max_points),
                errorTick = cur.errorTick + 1
            )
            return
        }
        val next = cur.waypoints + (lat to lon)
        internalState.value = cur.copy(
            waypoints = next, saved = false, error = null, routingError = false
        )
        scheduleRoad(next)
    }

    fun removeLast() {
        val cur = internalState.value
        if (cur.waypoints.isEmpty()) return
        startAutoPlaced = true
        val next = cur.waypoints.dropLast(1)
        internalState.value = cur.copy(
            waypoints = next, saved = false,
            roadGeometry = if (next.size < 2) emptyList() else cur.roadGeometry,
            distanceMeters = if (next.size < 2) 0f else cur.distanceMeters,
            error = null, routingError = false
        )
        scheduleRoad(next)
    }

    fun clearPoints() {
        roadJob?.cancel()
        // The user wants an empty map: the auto start point must not reappear.
        startAutoPlaced = true
        internalState.value = internalState.value.copy(
            waypoints = emptyList(), roadGeometry = emptyList(),
            distanceMeters = 0f, saved = false, error = null, routingError = false
        )
    }

    fun retryRoad() = scheduleRoad(internalState.value.waypoints, immediate = true)

    /**
     * Sprint 65: a tap on a marker in the builder map. The screen shows the confirm
     * dialog; only then [removeAt] runs. The start point (index 0) is editable like any
     * other one - the chain simply re-orders 1 -> 2 -> ... -> N.
     */
    fun removeAt(index: Int) {
        val cur = internalState.value
        if (index !in cur.waypoints.indices) return
        val next = cur.waypoints.toMutableList().apply { removeAt(index) }
        // Sprint 65: the user emptied the builder on purpose by tapping the last marker out,
        // so the auto start point is allowed to return on the next fix (unlike "Clear",
        // which explicitly means "I want an empty map").
        if (next.isEmpty()) startAutoPlaced = false
        internalState.value = cur.copy(
            waypoints = next, saved = false,
            roadGeometry = if (next.size < 2) emptyList() else cur.roadGeometry,
            distanceMeters = if (next.size < 2) 0f else cur.distanceMeters,
            error = null, routingError = false
        )
        if (next.isEmpty()) roadJob?.cancel()
        else scheduleRoad(next)
        // An emptied builder has no geometry left to draw, so a fresh fix is requested to
        // re-arm the automatic start point instead of leaving the camera on a deleted point.
        if (next.isEmpty()) requestFix(force = true)
    }

    /**
     * Sprint 65: a long-press drag of a marker on the map. Called ONCE on finger lift
     * (not per move event) so the OSRM debounce sees a single change and the public
     * routing server is not hammered while the finger travels.
     */
    fun movePoint(index: Int, lat: Double, lon: Double) {
        val cur = internalState.value
        if (index !in cur.waypoints.indices) return
        val target = cur.waypoints[index]
        // Sub-pixel jitter must not trigger a new routing round-trip.
        if (kotlin.math.abs(target.first - lat) < 1e-6 &&
            kotlin.math.abs(target.second - lon) < 1e-6
        ) return
        val next = cur.waypoints.toMutableList().apply { this[index] = lat to lon }
        internalState.value = cur.copy(
            waypoints = next, saved = false, error = null, routingError = false
        )
        scheduleRoad(next)
    }

    private fun scheduleRoad(waypoints: List<Pair<Double, Double>>, immediate: Boolean = false) {
        roadJob?.cancel()
        if (waypoints.size < 2) {
            internalState.value = internalState.value.copy(
                roadGeometry = emptyList(), distanceMeters = 0f, routingError = false
            )
            return
        }
        roadJob = viewModelScope.launch {
            if (!immediate) delay(ROAD_DEBOUNCE_MS)
            runCatching { routingClient.route(waypoints) }
                .onSuccess { route ->
                    internalState.value = internalState.value.copy(
                        roadGeometry = route.geometry,
                        distanceMeters = route.distanceMeters,
                        error = null, routingError = false
                    )
                }
                .onFailure {
                    // Honest failure: no silent straight line between the points.
                    internalState.value = internalState.value.copy(
                        roadGeometry = emptyList(),
                        distanceMeters = 0f,
                        routingError = true,
                        error = context.getString(R.string.route_routing_failed),
                        errorTick = internalState.value.errorTick + 1
                    )
                }
        }
    }

    suspend fun save(): Long? {
        val cur = internalState.value
        if (cur.roadGeometry.isEmpty() || cur.waypoints.size < 2) return null
        val route = buildRoute(cur) ?: return null
        val id = runCatching { plannedRouteRepository.save(route) }.getOrNull() ?: return null
        internalState.value = cur.copy(saved = true)
        return id
    }

    /**
     * Sprint 65: the plan behind "Начать маршрут".
     *
     * It is deliberately NOT written to `planned_routes`: the route only becomes a real
     * saved route (and shows up in "Сохранённые") when the user presses "Сохранить".
     * The returned object carries `id = 0`, is handed to [TrackingStateHolder] for this
     * recording session and is dropped when the ride ends - nothing can resurrect it in
     * the saved list later.
     */
    fun buildEphemeralPlan(): PlannedRoute? {
        val cur = internalState.value
        if (cur.roadGeometry.isEmpty() || cur.waypoints.size < 2) return null
        return buildRoute(cur)
    }

    private fun buildRoute(cur: RouteBuilderUiState): PlannedRoute? {
        if (cur.roadGeometry.isEmpty() || cur.waypoints.size < 2) return null
        return PlannedRoute(
            // id stays 0: an unsaved plan has no row in planned_routes.
            id = 0L,
            name = context.getString(R.string.route_default_name),
            distanceMeters = cur.distanceMeters,
            createdAt = System.currentTimeMillis(),
            geometry = cur.roadGeometry,
            waypoints = cur.waypoints.mapIndexed { i, p -> PlannedRoutePoint(p.first, p.second, i) }
        )
    }

    companion object {
        /** Sprint 62: a planned route is a chain of at most 10 points (1 -> 2 -> ... -> 10). */
        const val MAX_WAYPOINTS = 10
        const val ROAD_DEBOUNCE_MS = 500L
    }
}
