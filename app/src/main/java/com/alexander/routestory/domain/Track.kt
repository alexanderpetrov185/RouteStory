package com.alexander.routestory.domain

/** A recorded route with metadata, its points and photos. */
data class Track(
    val id: Long = 0L,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distance: Float,
    val duration: Long,
    val points: List<TrackPoint> = emptyList(),
    val photos: List<TrackPhoto> = emptyList()
)