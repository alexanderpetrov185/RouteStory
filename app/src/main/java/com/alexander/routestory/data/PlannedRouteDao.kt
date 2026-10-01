package com.alexander.routestory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedRouteDao {
    @Insert
    suspend fun insertRoute(route: PlannedRouteEntity): Long

    @Insert
    suspend fun insertPoints(points: List<PlannedRoutePointEntity>)

    @Transaction
    suspend fun insertRouteWithPoints(
        route: PlannedRouteEntity,
        points: List<PlannedRoutePointEntity>
    ): Long {
        val routeId = insertRoute(route)
        insertPoints(points.map { it.copy(routeId = routeId) })
        return routeId
    }

    @Query("SELECT * FROM planned_routes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlannedRouteEntity>>

    @Query("SELECT * FROM planned_routes WHERE id = :id")
    suspend fun getRouteById(id: Long): PlannedRouteEntity?

    @Query("SELECT * FROM planned_route_points WHERE routeId = :id ORDER BY orderIndex")
    suspend fun getPoints(id: Long): List<PlannedRoutePointEntity>

    /** Points are removed by the foreign key ON DELETE CASCADE rule. */
    @Query("DELETE FROM planned_routes WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun getRouteWithPoints(id: Long): Pair<PlannedRouteEntity, List<PlannedRoutePointEntity>>? {
        val route = getRouteById(id) ?: return null
        return route to getPoints(id)
    }
}
