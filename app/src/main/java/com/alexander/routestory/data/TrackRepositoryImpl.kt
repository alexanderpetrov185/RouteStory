package com.alexander.routestory.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

class TrackRepositoryImpl @Inject constructor(
    private val dao: TrackDao,
    private val photoDao: TrackPhotoDao,
    @ApplicationContext private val context: Context
) : TrackRepository {

    override suspend fun saveTrack(track: Track): Long {
        val entity = track.toEntity()
        return dao.insertTrackWithPoints(entity, track.points.map { it.toEntity(entity.id) },
            track.photos.map { TrackPhotoEntity(trackId = 0, uri = it.uri, timestamp = it.timestamp,
                latitude = it.latitude, longitude = it.longitude) })
    }

    override fun observeTracks(): Flow<List<Track>> =
        dao.getAllTracks().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrack(id: Long): Track? {
        val entity = dao.getTrackById(id) ?: return null
        val points = dao.getPointsForTrack(id).map { it.toDomain() }
        val photos = photoDao.getPhotosForTrack(id).map { it.toDomain() }
        return entity.toDomain().copy(points = points, photos = photos)
    }

    override suspend fun deleteTrack(id: Long) {
        photoDao.getPhotosForTrack(id).forEach { deletePhoto(it.toDomain()) }
        dao.deleteTrackById(id)
    }

    override suspend fun renameTrack(id: Long, name: String) {
        dao.renameTrack(id, name)
    }

    override suspend fun addPhoto(track: Track, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val dest = File(dir, track.id.toString() + "_" + UUID.randomUUID() + ".jpg")
            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) return@runCatching false

            var timestamp = (track.startTime + track.endTime) / 2
            var latitude: Double? = null
            var longitude: Double? = null
            try {
                val exif = ExifInterface(dest.absolutePath)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                if (raw != null) {
                    val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    format.timeZone = TimeZone.getDefault()
                    timestamp = format.parse(raw)?.time ?: timestamp
                }
                exif.latLong?.let {
                    latitude = it[0].toDouble()
                    longitude = it[1].toDouble()
                }
            } catch (_: Exception) {
                // No EXIF: fall back to defaults above
            }
            photoDao.insertPhoto(
                TrackPhotoEntity(
                    trackId = track.id,
                    uri = dest.absolutePath,
                    timestamp = timestamp,
                    latitude = latitude,
                    longitude = longitude
                )
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun deletePhoto(photo: TrackPhoto) = withContext(Dispatchers.IO) {
        photoDao.deletePhotoById(photo.id)
        try {
            File(photo.uri).delete()
        } catch (_: Exception) {
            // File already gone
        }
        Unit
    }

    override suspend fun updatePhotoTimestamp(photo: TrackPhoto, newTimestamp: Long) {
        photoDao.updatePhotoTimestamp(photo.id, newTimestamp)
    }
}