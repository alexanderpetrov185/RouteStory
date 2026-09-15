package com.alexander.routestory.domain

/** A photo attached to a track, optionally geo-referenced. */
data class TrackPhoto(
    val id: Long = 0L,
    val trackId: Long,
    val uri: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?
)