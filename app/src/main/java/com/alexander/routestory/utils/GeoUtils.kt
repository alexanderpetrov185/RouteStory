package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS_M = 6371000.0

    /** Haversine distance between two points, in meters. */
    fun distanceMeters(p1: TrackPoint, p2: TrackPoint): Float =
        distanceMeters(p1.lat, p1.lon, p2.lat, p2.lon)

    /** Haversine distance for raw coordinates, in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).pow(2) + cos(radLat1) * cos(radLat2) * sin(dLon / 2).pow(2)
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

    /**
     * Sprint 68: the whole ride window in the DEVICE time zone, e.g. "12.05.2026, 08:14-09:02".
     * A ride that crosses midnight keeps both dates, so an evening-to-night trip never reads
     * as a broken range.
     */
    fun formatTimeRange(startMs: Long, endMs: Long): String {
        if (startMs <= 0L) return formatDate(endMs)
        if (endMs <= 0L) return formatDate(startMs)
        val locale = java.util.Locale.getDefault()
        val day = SimpleDateFormat("dd.MM.yyyy", locale)
        val time = SimpleDateFormat("HH:mm", locale)
        val from = Date(startMs)
        val to = Date(endMs)
        return if (day.format(from) == day.format(to)) {
            day.format(from) + ", " + time.format(from) + "\u2013" + time.format(to)
        } else {
            day.format(from) + " " + time.format(from) + " \u2013 " +
                day.format(to) + " " + time.format(to)
        }
    }

    /** Elevation, rounded to metres ("482 м"). */
    fun formatElevation(meters: Float): String = String.format("%.0f \u043C", meters)

    /**
     * Sprint 68: total climb of a track, in metres.
     *
     * GPS altitude is noisy (±3-5 m outdoors, far worse indoors), so a plain sum of positive
     * deltas would report hundreds of metres of "climb" on a flat standstill ride. Only a
     * reversal larger than [hysteresisM] counts as a real slope change: the reference altitude
     * follows the track, the small jitter inside the band is ignored.
     */
    fun totalAscent(points: List<TrackPoint>, hysteresisM: Double = 3.0): Float =
        climb(points, hysteresisM).first

    /** Total descent, mirror image of [totalAscent]. */
    fun totalDescent(points: List<TrackPoint>, hysteresisM: Double = 3.0): Float =
        climb(points, hysteresisM).second

    /**
     * Sprint 68: cumulative ascent after EVERY point, same hysteresis rule as [totalAscent].
     * The video HUD shows the climb reached at the current marker, and this keeps that value
     * monotonic along the route instead of recomputing the whole profile per frame.
     */
    fun cumulativeAscent(points: List<TrackPoint>, hysteresisM: Double = 3.0): FloatArray {
        val out = FloatArray(points.size)
        if (points.isEmpty()) return out
        var ascent = 0.0
        var reference = points.first().altitude
        for (index in points.indices) {
            val delta = points[index].altitude - reference
            if (delta > hysteresisM) {
                ascent += delta
                reference = points[index].altitude
            } else if (delta < -hysteresisM) {
                reference = points[index].altitude
            }
            out[index] = ascent.toFloat()
        }
        return out
    }

    /**
     * Sprint 70: cumulative DESCENT after every point — the mirror of
     * [cumulativeAscent] (same hysteresis reference rule): a real downhill
     * accumulates, the ±1 m noise inside the band is ignored. Lets the tracking
     * caption and the video HUD show "▼ Y m so far", not just the climb.
     */
    fun cumulativeDescent(points: List<TrackPoint>, hysteresisM: Double = 3.0): FloatArray {
        val out = FloatArray(points.size)
        if (points.isEmpty()) return out
        var descent = 0.0
        var reference = points.first().altitude
        for (index in points.indices) {
            val delta = points[index].altitude - reference
            if (delta < -hysteresisM) {
                descent += -delta
                reference = points[index].altitude
            } else if (delta > hysteresisM) {
                reference = points[index].altitude
            }
            out[index] = descent.toFloat()
        }
        return out
    }

    /**
     * Sprint 70: smoothed altitude series for elevation CHARTS (ups and downs),
     * in the same metres as the raw fixes. A centred moving average over raw
     * altitudes kills the indoor ±1 m sawtooth but keeps real hill shapes ([window]
     * points, odd, truncated at the ends; the raw series for < 3 points).
     * Hysteresis would quantise the chart into 3 m steps; a window keeps it wavy.
     * Chart code MUST normalise by (min..max), not by max alone.
     */
    fun elevationProfile(points: List<TrackPoint>, window: Int = 5): FloatArray {
        val out = FloatArray(points.size)
        if (points.isEmpty()) return out
        if (points.size < 3) {
            for (i in points.indices) out[i] = points[i].altitude.toFloat()
            return out
        }
        val half = (window.coerceAtLeast(1) / 2).coerceAtLeast(1)
        for (i in points.indices) {
            var sum = 0.0
            var count = 0
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(points.size - 1)
            for (k in from..to) {
                sum += points[k].altitude
                count++
            }
            out[i] = (sum / count).toFloat()
        }
        return out
    }

    /**
     * One pass over the altitude profile that yields BOTH totals.
     *
     * The reference altitude is only moved when the track reverses by more than
     * [hysteresisM]; that single rule covers ascent and descent at once. Tracking the two
     * directions separately (two independent passes) silently lost the second climb of an
     * up-down-up profile, because the descent pass never re-based its own reference.
     *
     * @return (ascent, descent) in metres.
     */
    private fun climb(points: List<TrackPoint>, hysteresisM: Double): Pair<Float, Float> {
        if (points.size < 2) return 0f to 0f
        var ascent = 0.0
        var descent = 0.0
        var reference = points.first().altitude
        for (index in 1 until points.size) {
            val altitude = points[index].altitude
            val delta = altitude - reference
            if (delta > hysteresisM) {
                ascent += delta
                reference = altitude
            } else if (delta < -hysteresisM) {
                descent += -delta
                reference = altitude
            }
        }
        return ascent.toFloat() to descent.toFloat()
    }

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

    /** Returns the normalized Web Mercator X and Y coordinates [0, 1]. */
    fun getWebMercatorRel(lat: Double, lon: Double): Pair<Double, Double> {
        val x = (lon + 180.0) / 360.0
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        return x to y
    }
}
