package com.alexander.routestory.domain

/** A single GPS point of a recorded track. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val speed: Float,
    val accuracy: Float,
    val timestamp: Long
)