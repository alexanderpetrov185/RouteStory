package com.alexander.routestory.video

import com.alexander.routestory.domain.TrackPoint
import kotlin.math.*

/** Distance-based timeline, independent of GPS timestamps and sampling frequency. */
internal class RouteVideoMotion(points: List<TrackPoint>) {
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
