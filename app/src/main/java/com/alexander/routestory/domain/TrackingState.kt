package com.alexander.routestory.domain

enum class TrackingState { IDLE, RECORDING, PAUSED }

data class TrackingUiState(
    val state: TrackingState = TrackingState.IDLE,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Float = 0f,
    val durationMs: Long = 0L,
    val speedMps: Float = 0f
)