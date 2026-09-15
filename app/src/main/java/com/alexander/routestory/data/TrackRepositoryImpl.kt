package com.alexander.routestory.data

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TrackRepositoryImpl @Inject constructor(
    private val dao: TrackDao
) : TrackRepository {
    override suspend fun saveTrack(track: Track): Long {
        val entity = track.toEntity()
        return dao.insertTrackWithPoints(entity, track.points.map { it.toEntity(entity.id) })
    }

    override fun observeTracks(): Flow<List<Track>> =
        dao.getAllTracks().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrack(id: Long): Track? {
        val entity = dao.getTrackById(id) ?: return null
        val points = dao.getPointsForTrack(id).map { it.toDomain() }
        return entity.toDomain().copy(points = points)
    }

    override suspend fun deleteTrack(id: Long) = dao.deleteTrackById(id)
}