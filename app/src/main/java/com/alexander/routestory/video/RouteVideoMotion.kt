package com.alexander.routestory.video

import com.alexander.routestory.domain.TrackPoint
import kotlin.math.*

/** Distance-based timeline, independent of GPS timestamps and sampling frequency. */
internal class RouteVideoMotion(points: List<TrackPoint>) {
    /** Kept so a geo position can be projected back onto the polyline. */
    private val pts: List<TrackPoint> = points
    private val cumulative = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val hav = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) *
                cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            cumulative[i] = cumulative[i - 1] + 2 * 6_371_000 * asin(sqrt(hav.coerceIn(0.0, 1.0)))
        }
    }

    fun lengthMeters(): Double = cumulative.lastOrNull() ?: 0.0

    fun position(progress: Float): Float {
        if (cumulative.size < 2) return 0f
        val p = progress.coerceIn(0f, 1f)
        val total = cumulative.last()
        if (total <= 0.001) return p * cumulative.lastIndex
        val distance = total * p
        var i = 0
        while (i < cumulative.lastIndex - 1 && cumulative[i + 1] <= distance) i++
        val segment = cumulative[i + 1] - cumulative[i]
        return i + if (segment > 0) ((distance - cumulative[i]) / segment).toFloat() else 0f
    }

    /**
     * Sprint 40: distance fraction (0..1) at a CONTINUOUS route position
     * (index + fraction), i.e. the exact geo point, not the nearest GPS fix.
     * This is the single definition shared by the moving marker and the photo
     * pins, so a photo pause opens exactly when the marker reaches its pin.
     */
    fun fractionAtPosition(pos: Float): Float {
        if (cumulative.size < 2) return 0f
        val total = cumulative.last()
        val last = cumulative.lastIndex
        if (total <= 0.001) return (pos / last.toFloat()).coerceIn(0f, 1f)
        val clamped = pos.coerceIn(0f, last.toFloat())
        val i = clamped.toInt().coerceAtMost(last - 1)
        val t = (clamped - i).coerceIn(0f, 1f)
        val d = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t
        return (d / total).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Sprint 40: continuous route position (index + fraction) of the point on the
     * polyline closest to ([lat], [lon]). Used to bind a photo to the very same
     * point the pin is drawn at instead of the nearest fix (up to half a sample
     * of error with 1 s GPS logging).
     */
    fun positionOf(lat: Double, lon: Double): Float {
        if (pts.size < 2) return 0f
        var bestPos = 0f
        var bestD = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            // Locally flat (equirectangular) projection: enough for a per-segment t.
            val sc = cos(Math.toRadians((a.lat + b.lat) / 2))
            val ax = a.lon * sc
            val ay = a.lat
            val vx = b.lon * sc - ax
            val vy = b.lat - ay
            val px = lon * sc - ax
            val py = lat - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 <= 1e-18) 0.0 else ((px * vx + py * vy) / len2).coerceIn(0.0, 1.0)
            val dx = vx * t - px
            val dy = vy * t - py
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                bestPos = i + t.toFloat()
            }
        }
        return bestPos
    }

    /** Distance fraction (0..1) of the route point at [index]. */
    fun fractionAt(index: Int): Float {
        if (cumulative.size < 2) return 0f
        val total = cumulative.last()
        if (total <= 0.001) return index.toFloat() / cumulative.lastIndex
        return (cumulative[index.coerceIn(0, cumulative.lastIndex)] / total).toFloat()
    }

    companion object {
        fun progress(frame: Int, frameCount: Int): Float =
            (frame.toFloat() / (frameCount - 1).coerceAtLeast(1)).coerceIn(0f, 1f)

        // First 10% overview, next 20% smooth approach, then 2x follow.
        fun followBlend(progress: Float): Float {
            val t = ((progress - 0.1f) / 0.2f).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
