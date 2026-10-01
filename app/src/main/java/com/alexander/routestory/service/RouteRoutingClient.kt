package com.alexander.routestory.service

import com.alexander.routestory.domain.PlannedRoute
import com.alexander.routestory.domain.PlannedRoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OSRM road routing (driving profile). Tiles remain the only other network usage.
 *
 * Sprint 62: all waypoints go into ONE request (`lat,lon;lat,lon;...`), so OSRM itself returns a
 * single continuous road geometry for the chain 1 -> 2 -> ... -> N plus its total distance.
 * Gluing N-1 segment responses instead would mean N-1 round-trips against the rate-limited public
 * server, visible seams between segments and a distance sum with per-leg rounding.
 */
@Singleton
class RouteRoutingClient @Inject constructor(
    private val client: OkHttpClient
) {
    suspend fun route(waypoints: List<Pair<Double, Double>>): PlannedRoute = withContext(Dispatchers.IO) {
        require(waypoints.size in 2..MAX_ROUTE_WAYPOINTS) { "Нужно от 2 до $MAX_ROUTE_WAYPOINTS точек" }
        val coords = waypoints.joinToString(";") { "${it.second},${it.first}" }
        val request = Request.Builder()
            .url("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson")
            .header("User-Agent", "RouteStory/0.0.3 (Android route planner)")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OSRM HTTP ${response.code}" }
            val root = JSONObject(response.body?.string().orEmpty())
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
            val geometry = buildList {
                for (i in 0 until coordinates.length()) {
                    val pair = coordinates.getJSONArray(i)
                    add(PlannedRoutePoint(pair.getDouble(1), pair.getDouble(0), i))
                }
            }
            PlannedRoute(
                name = "Маршрут", distanceMeters = route.getDouble("distance").toFloat(),
                createdAt = System.currentTimeMillis(), geometry = geometry,
                waypoints = waypoints.mapIndexed { i, p -> PlannedRoutePoint(p.first, p.second, i) }
            )
        }
    }

    companion object {
        /** Sprint 62: the builder allows at most 10 waypoints in one chain. */
        const val MAX_ROUTE_WAYPOINTS = 10
    }

}
