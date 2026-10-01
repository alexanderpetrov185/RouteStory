package com.alexander.routestory.data

import com.alexander.routestory.domain.PlannedRoute
import com.alexander.routestory.domain.PlannedRoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannedRouteRepositoryImpl @Inject constructor(
    private val dao: PlannedRouteDao
) : PlannedRouteRepository {

    override fun observeAll(): Flow<List<PlannedRoute>> =
        dao.observeAll().map { list -> list.map { it.toDomain(emptyList()) } }

    override suspend fun getById(id: Long): PlannedRoute? = withContext(Dispatchers.IO) {
        val (route, points) = dao.getRouteWithPoints(id) ?: return@withContext null
        route.toDomain(points.map { PlannedRoutePoint(it.latitude, it.longitude, it.orderIndex) })
    }

    override suspend fun save(route: PlannedRoute): Long = withContext(Dispatchers.IO) {
        dao.insertRouteWithPoints(
            route.toEntity(),
            route.waypoints.mapIndexed { i, p ->
                PlannedRoutePointEntity(
                    routeId = 0L, orderIndex = i,
                    latitude = p.latitude, longitude = p.longitude
                )
            }
        )
    }

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }
}

internal fun PlannedRouteEntity.toDomain(waypoints: List<PlannedRoutePoint>): PlannedRoute =
    PlannedRoute(
        id = id, name = name, profile = profile, distanceMeters = distanceMeters,
        createdAt = createdAt, geometry = geometry.decodeGeometry(), waypoints = waypoints
    )

internal fun PlannedRoute.toEntity(): PlannedRouteEntity = PlannedRouteEntity(
    id = 0L, name = name, profile = profile, distanceMeters = distanceMeters,
    createdAt = createdAt, geometry = geometry.encodeGeometry()
)

internal fun List<PlannedRoutePoint>.encodeGeometry(): String =
    joinToString(";") { "${it.latitude},${it.longitude}" }

internal fun String.decodeGeometry(): List<PlannedRoutePoint> {
    if (isBlank()) return emptyList()
    return split(";").mapIndexedNotNull { i, part ->
        val nums = part.split(",").mapNotNull { it.toDoubleOrNull() }
        if (nums.size != 2) null else PlannedRoutePoint(nums[0], nums[1], i)
    }
}
