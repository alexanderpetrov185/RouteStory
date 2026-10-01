package com.alexander.routestory.domain

enum class TrackingState { IDLE, RECORDING, PAUSED }

data class TrackingUiState(
    val state: TrackingState = TrackingState.IDLE,
    val sessionId: String = "",
    /** Wall-clock start of the ride; survives a service restart so saved dates stay real. */
    val startTimeMs: Long = 0L,
    /** Auto-generated ride name, kept with the session for the same reason. */
    val trackName: String = "",
    val photos: List<TrackPhoto> = emptyList(),
    val capturePending: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Float = 0f,
    val durationMs: Long = 0L,
    val speedMps: Float = 0f,
    /** Latest raw GPS fix, even if the point was filtered out of the recorded track. */
    val lastFix: Pair<Double, Double>? = null,
    val activePlannedRouteId: Long? = null,
    val plannedRoute: com.alexander.routestory.domain.PlannedRoute? = null,
    /** A killed session left a recoverable draft on disk. */
    val draftAvailable: Boolean = false
)