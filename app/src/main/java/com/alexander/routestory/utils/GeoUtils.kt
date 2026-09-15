package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine distance between two points, in meters. */
    fun distanceMeters(p1: TrackPoint, p2: TrackPoint): Float {
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLon = Math.toRadians(p2.lon - p1.lon)
        val lat1 = Math.toRadians(p1.lat)
        val lat2 = Math.toRadians(p2.lat)
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return (2 * EARTH_RADIUS_M * asin(sqrt(a))).toFloat()
    }

    fun formatDistance(meters: Float): String =
        if (meters < 1000f) String.format("%.0f \u043C", meters)
        else String.format("%.2f \u043A\u043C", meters / 1000f)

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%d:%02d:%02d", h, m, s)
    }

    fun formatSpeed(mps: Float): String = String.format("%.1f \u043A\u043C/\u0447", mps * 3.6f)

    fun formatDate(ms: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(Date(ms))
    /** Linearly interpolates a lat/lon position on the route by timestamp. */
    fun interpolateByTime(points: List<TrackPoint>, timestamp: Long): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        if (timestamp <= points.first().timestamp) return points.first().let { it.lat to it.lon }
        if (timestamp >= points.last().timestamp) return points.last().let { it.lat to it.lon }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (timestamp <= b.timestamp) {
                val span = (b.timestamp - a.timestamp).toFloat()
                val t = if (span > 0f) (timestamp - a.timestamp) / span else 0f
                return Pair(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        return null
    }
}
