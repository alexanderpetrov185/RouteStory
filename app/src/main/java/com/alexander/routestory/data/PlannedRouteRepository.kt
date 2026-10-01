package com.alexander.routestory.data

import com.alexander.routestory.domain.PlannedRoute
import kotlinx.coroutines.flow.Flow

interface PlannedRouteRepository {
    fun observeAll(): Flow<List<PlannedRoute>>
    suspend fun getById(id: Long): PlannedRoute?
    suspend fun save(route: PlannedRoute): Long
    suspend fun delete(id: Long)
}
