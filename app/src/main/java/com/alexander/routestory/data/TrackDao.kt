package com.alexander.routestory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Insert
    suspend fun insertPhotos(photos: List<TrackPhotoEntity>)

    /** Saves track, points and photos atomically, assigning the generated track ID. */
    @Transaction
    suspend fun insertTrackWithPoints(track: TrackEntity, points: List<TrackPointEntity>, photos: List<TrackPhotoEntity> = emptyList()): Long {
        val trackId = insertTrack(track)
        insertPoints(points.map { it.copy(trackId = trackId) })
        insertPhotos(photos.map { it.copy(trackId = trackId) })
        return trackId
    }

    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun renameTrack(id: Long, name: String)

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getPointsForTrack(trackId: Long): List<TrackPointEntity>

    /** Points are removed by the foreign key ON DELETE CASCADE rule. */
    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)
}