package com.alexander.routestory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TrackPhotoDao {
    @Insert
    suspend fun insertPhoto(photo: TrackPhotoEntity): Long

    @Query("SELECT * FROM track_photos WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getPhotosForTrack(trackId: Long): List<TrackPhotoEntity>

    @Query("UPDATE track_photos SET timestamp = :timestamp WHERE id = :photoId")
    suspend fun updatePhotoTimestamp(photoId: Long, timestamp: Long)

    @Query("DELETE FROM track_photos WHERE id = :photoId")
    suspend fun deletePhotoById(photoId: Long)
}