package com.alexander.routestory.domain

import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    suspend fun saveTrack(track: Track): Long

    /** Track metadata only, without points. Ordered by startTime DESC. */
    fun observeTracks(): Flow<List<Track>>

    /** A single track with all of its points. */
    suspend fun getTrack(id: Long): Track?

    suspend fun deleteTrack(id: Long)
}