package com.alexander.routestory.domain

enum class TrackingState { IDLE, RECORDING, PAUSED }

data class TrackingUiState(
    val state: TrackingState = TrackingState.IDLE,
    val sessionId: String = "",
    val photos: List<TrackPhoto> = emptyList(),
    val capturePending: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Float = 0f,
    val durationMs: Long = 0L,
    val speedMps: Float = 0f,
    /** Latest raw GPS fix, even if the point was filtered out of the recorded track. */
    val lastFix: Pair<Double, Double>? = null,
    /** A killed session left a recoverable draft on disk. */
    val draftAvailable: Boolean = false
)