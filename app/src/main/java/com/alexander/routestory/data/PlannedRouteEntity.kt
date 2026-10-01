package com.alexander.routestory.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "planned_routes")
data class PlannedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val profile: String = "driving",
    val distanceMeters: Float,
    val createdAt: Long,
    /** Road geometry encoded as "lat,lon;lat,lon;..." (offline-first, no re-fetch). */
    val geometry: String = ""
)

@Entity(
    tableName = "planned_route_points",
    foreignKeys = [ForeignKey(
        entity = PlannedRouteEntity::class,
        parentColumns = ["id"],
        childColumns = ["routeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("routeId")]
)
data class PlannedRoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val routeId: Long = 0L,
    val orderIndex: Int,
    val latitude: Double,
    val longitude: Double
)
