package com.alexander.routestory.domain

data class PlannedRoutePoint(val latitude: Double, val longitude: Double, val order: Int)

data class PlannedRoute(
    val id: Long = 0L,
    val name: String,
    val profile: String = "driving",
    val distanceMeters: Float,
    val createdAt: Long,
    val geometry: List<PlannedRoutePoint> = emptyList(),
    val waypoints: List<PlannedRoutePoint> = emptyList()
)
