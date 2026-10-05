package com.alexander.routestory.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.alexander.routestory.domain.Track
import com.alexander.routestory.video.MapSnapshotProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Sprint 69: high-quality trip photo (bitmap) for gallery save / share.
 * On-device Canvas render, no new SDKs. OOM care: one 1080x1350 ARGB_8888
 * (~5.8 MiB) at a time, mosaic bitmaps recycled right after drawing.
 */
@Singleton
class TripSnapshotRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotProvider: MapSnapshotProvider
) {
    enum class Style { SCHEMATIC, SATELLITE }

    suspend fun render(track: Track, style: Style): Bitmap? = withContext(Dispatchers.IO) {
        if (track.points.isEmpty()) return@withContext null
        runCatching {
            val w = CARD_W
            val h = CARD_H
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val mapH = h - STATS_H
            val mapBmp = when (style) {
                Style.SCHEMATIC -> osmBackdrop(track, w, mapH)
                Style.SATELLITE -> snapshotProvider.snapshot(track, w, mapH)
            }
            if (mapBmp != null) {
                canvas.drawBitmap(mapBmp, 0f, 0f, null)
                if (!mapBmp.isRecycled) mapBmp.recycle()
            } else {
                val fill = if (style == Style.SATELLITE) Color.rgb(24, 28, 34)
                else Color.rgb(232, 234, 237)
                canvas.drawColor(fill)
            }
            drawRoute(canvas, track, w, mapH)
            drawElevationCard(canvas, track, w)
            drawStatsCard(canvas, track, w, h)
            out
        }.getOrNull()
    }

    private fun projectAll(track: Track, w: Int, h: Int): List<Pair<Float, Float>> {
        val pts = track.points
        val lats = pts.map { it.lat }
        val lons = pts.map { it.lon }
        val minLat = lats.minOrNull()!!
        val maxLat = lats.maxOrNull()!!
        val minLon = lons.minOrNull()!!
        val maxLon = lons.maxOrNull()!!
        fun yMerc(lat: Double): Double {
            val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0
        }
        val x0 = (minLon + 180.0) / 360.0
        val x1 = (maxLon + 180.0) / 360.0
        val y0 = yMerc(maxLat)
        val y1 = yMerc(minLat)
        val pad = 0.12f
        return pts.map { p ->
            val fx = if (x1 > x0) ((((p.lon + 180.0) / 360.0) - x0) / (x1 - x0)).toFloat() else 0.5f
            val fy = if (y1 > y0) ((yMerc(p.lat) - y0) / (y1 - y0)).toFloat() else 0.5f
            (pad + fx * (1 - 2 * pad)) * w to (pad + fy * (1 - 2 * pad)) * h
        }
    }

    private fun drawRoute(canvas: Canvas, track: Track, w: Int, h: Int) {
        val xy = projectAll(track, w, h)
        if (xy.size < 2) return
        val path = Path().apply {
            moveTo(xy[0].first, xy[0].second)
            for (i in 1 until xy.size) lineTo(xy[i].first, xy[i].second)
        }
        val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 13f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val over = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 134, 193)
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, under)
        canvas.drawPath(path, over)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = Color.rgb(46, 204, 113)
        canvas.drawCircle(xy.first().first, xy.first().second, 14f, dot)
        dot.color = Color.rgb(231, 76, 60)
        canvas.drawCircle(xy.last().first, xy.last().second, 14f, dot)
    }

    private suspend fun osmBackdrop(track: Track, w: Int, h: Int): Bitmap? {
        val pts = track.points
        val lats = pts.map { it.lat }
        val lons = pts.map { it.lon }
        val centerLat = (lats.minOrNull()!! + lats.maxOrNull()!!) / 2
        val centerLon = (lons.minOrNull()!! + lons.maxOrNull()!!) / 2
        val lonSpan = ((lons.maxOrNull()!! - lons.minOrNull()!!) * 1.3).coerceAtLeast(0.01)
        val latSpan = ((lats.maxOrNull()!! - lats.minOrNull()!!) * 1.3).coerceAtLeast(0.01)
        val mosaic = OsmTileLayer.mosaic(
            context, centerLat, centerLon, lonSpan, latSpan, tiles = 4
        ) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val src = Rect(0, 0, mosaic.bitmap.width, mosaic.bitmap.height)
        val dst = Rect(0, 0, w, h)
        canvas.drawBitmap(mosaic.bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        mosaic.bitmap.recycle()
        return out
    }

    private fun drawStatsCard(canvas: Canvas, track: Track, w: Int, h: Int) {
        val cardH = STATS_H.toFloat() - 48f
        val left = 48f
        val top = (h - STATS_H).toFloat() + 24f
        val rect = RectF(left, top, w - 48f, top + cardH)
        canvas.drawRoundRect(rect, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
        })
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(33, 33, 33)
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 66, 66)
            textSize = 32f
        }
        val avg = if (track.duration > 0) track.distance / (track.duration / 1000f) else 0f
        val max = track.points.maxOfOrNull { it.speed } ?: 0f
        val ascent = GeoUtils.totalAscent(track.points)
        val descent = GeoUtils.totalDescent(track.points)
        val lines = listOf(
            GeoUtils.formatTimeRange(track.startTime, track.endTime),
            GeoUtils.formatDistance(track.distance) + "  ·  " + GeoUtils.formatDuration(track.duration),
            "Ср. " + GeoUtils.formatSpeed(avg) + "  Макс. " + GeoUtils.formatSpeed(max),
            // Sprint 70: descent next to the climb (was climb-only).
            "Набор " + GeoUtils.formatElevation(ascent) + "  ·  Сброс " +
                GeoUtils.formatElevation(descent)
        )
        var y = top + 60f
        canvas.drawText(track.name.take(34), left + 32f, y, title)
        y += 54f
        for (line in lines) {
            canvas.drawText(line, left + 32f, y, small)
            y += 46f
        }
    }

    // Sprint 70: the relief (ups and downs, normalised min..max) — the old
    // monotone cumulative-climb chart always grew and hid every descent.
    private fun drawElevationCard(canvas: Canvas, track: Track, w: Int) {
        val profile = GeoUtils.elevationProfile(track.points)
        val min = profile.minOrNull() ?: 0f
        val range = (profile.maxOrNull() ?: 0f) - min
        val cw = 340f
        val ch = 190f
        val left = w - cw - 36f
        val top = 36f
        val rect = RectF(left, top, left + cw, top + ch)
        canvas.drawRoundRect(rect, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 20, 24, 30)
        })
        val ascent = GeoUtils.totalAscent(track.points)
        val descent = GeoUtils.totalDescent(track.points)
        canvas.drawText(
            "▲ " + GeoUtils.formatElevation(ascent) +
                "  ▼ " + GeoUtils.formatElevation(descent),
            left + 20f, top + 38f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
            }
        )
        if (profile.size < 2 || range <= 0.5f) return
        val padL = 20f
        val padB = 20f
        fun yOf(value: Float): Float =
            top + ch - padB - ((value - min) / range) * (ch - 70f)
        val area = Path()
        val line = Path()
        for (i in profile.indices) {
            val frac = if (profile.size > 1) i.toFloat() / (profile.size - 1) else 0f
            val x = left + padL + frac * (cw - 2 * padL)
            val y = yOf(profile[i])
            if (i == 0) {
                area.moveTo(x, top + ch - padB)
                area.lineTo(x, y)
                line.moveTo(x, y)
            } else {
                area.lineTo(x, y)
                line.lineTo(x, y)
            }
        }
        area.lineTo(left + cw - padL, top + ch - padB)
        area.close()
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 76, 175, 80)
        })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(165, 255, 176)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        })
    }

    companion object {
        const val CARD_W = 1080
        const val CARD_H = 1350
        const val STATS_H = 340
    }
}
