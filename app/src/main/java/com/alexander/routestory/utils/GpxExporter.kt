package com.alexander.routestory.utils

import com.alexander.routestory.domain.Track
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds a GPX 1.1 document from a track. Pure Kotlin, no Android dependencies. */
object GpxExporter {

    private fun newIsoFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun buildGpx(track: Track): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine()
        append("<gpx version=\"1.1\" creator=\"RouteStory\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        appendLine()
        append("  <metadata><time>")
        append(newIsoFormat().format(Date(track.startTime)))
        append("</time></metadata>")
        appendLine()
        append("  <trk>")
        appendLine()
        append("    <name>")
        append(escapeXml(track.name))
        append("</name>")
        appendLine()
        append("    <trkseg>")
        appendLine()
        for (point in track.points) {
            append("      <trkpt lat=\"")
            append(point.lat)
            append("\" lon=\"")
            append(point.lon)
            append("\"><ele>")
            append(point.altitude)
            append("</ele><time>")
            append(newIsoFormat().format(Date(point.timestamp)))
            append("</time></trkpt>")
            appendLine()
        }
        append("    </trkseg>")
        appendLine()
        append("  </trk>")
        appendLine()
        append("</gpx>")
        appendLine()
    }

    fun suggestFileName(track: Track): String =
        track.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".gpx"

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}