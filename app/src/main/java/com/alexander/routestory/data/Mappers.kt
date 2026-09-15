package com.alexander.routestory.data

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint

fun TrackEntity.toDomain(): Track = Track(
    id = id, name = name, startTime = startTime, endTime = endTime,
    distance = distance, duration = duration
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id, name = name, startTime = startTime, endTime = endTime,
    distance = distance, duration = duration
)

fun TrackPointEntity.toDomain(): TrackPoint = TrackPoint(
    lat = lat, lon = lon, altitude = altitude, speed = speed,
    accuracy = accuracy, timestamp = timestamp
)

fun TrackPoint.toEntity(trackId: Long): TrackPointEntity = TrackPointEntity(
    trackId = trackId, lat = lat, lon = lon, altitude = altitude,
    speed = speed, accuracy = accuracy, timestamp = timestamp
)

fun TrackPhotoEntity.toDomain(): TrackPhoto = TrackPhoto(
    id = id, trackId = trackId, uri = uri, timestamp = timestamp,
    latitude = latitude, longitude = longitude
)

fun TrackPhoto.toEntity(trackId: Long): TrackPhotoEntity = TrackPhotoEntity(
    id = id, trackId = trackId, uri = uri, timestamp = timestamp,
    latitude = latitude, longitude = longitude
)
