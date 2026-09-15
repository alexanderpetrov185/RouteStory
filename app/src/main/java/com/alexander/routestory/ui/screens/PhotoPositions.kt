package com.alexander.routestory.ui.screens

import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.utils.GeoUtils

/** Single place that maps photos onto route positions: EXIF coords win, otherwise time interpolation. */
fun photoPositionsOf(points: List<TrackPoint>, photos: List<TrackPhoto>): List<Pair<Double, Double>> =
    photos.mapNotNull { photo ->
        photo.latitude?.let { lat ->
            photo.longitude?.let { lon -> lat to lon }
        } ?: GeoUtils.interpolateByTime(points, photo.timestamp)
    }
