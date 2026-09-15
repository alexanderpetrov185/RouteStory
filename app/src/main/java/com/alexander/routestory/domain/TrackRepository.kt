package com.alexander.routestory.domain

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    suspend fun saveTrack(track: Track): Long

    /** Track metadata only, without points. Ordered by startTime DESC. */
    fun observeTracks(): Flow<List<Track>>

    /** A single track with all of its points and photos. */
    suspend fun getTrack(id: Long): Track?

    suspend fun deleteTrack(id: Long)

    /** Copies the photo into app storage, reads EXIF and inserts a DB row. */
    suspend fun addPhoto(track: Track, sourceUri: Uri): Boolean

    /** Removes the DB row and the stored file. */
    suspend fun deletePhoto(photo: TrackPhoto)

    suspend fun updatePhotoTimestamp(photo: TrackPhoto, newTimestamp: Long)
}