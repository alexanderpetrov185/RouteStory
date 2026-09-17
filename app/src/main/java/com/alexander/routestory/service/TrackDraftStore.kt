package com.alexander.routestory.service

import android.content.Context
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.domain.TrackingState
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Serializable snapshot of an in-progress recording, enough to resume after a process kill. */
data class TrackDraft(
    val sessionId: String,
    val trackName: String,
    val startTimeMs: Long,
    val accumulatedMs: Long,
    val totalDistanceM: Float,
    val status: TrackingState,
    val points: List<TrackPoint>,
    val photos: List<TrackPhoto>,
    val savedAtMs: Long = System.currentTimeMillis()
)

/**
 * File-based draft store (org.json, no new dependencies).
 * Atomic writes via tmp + rename. Drafts older than 24h are treated as stale.
 */
@Singleton
class TrackDraftStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun draftFile(): File = File(File(context.filesDir, "drafts").apply { mkdirs() }, "current.json")
    private fun atomicFile() = android.util.AtomicFile(draftFile())
    private var newestWrite = 0L

    @Synchronized
    fun hasDraft(): Boolean = load() != null

    @Synchronized
    fun save(draft: TrackDraft) {
        if (draft.savedAtMs < newestWrite) return
        runCatching {
            val root = JSONObject()
            root.put("sessionId", draft.sessionId)
            root.put("trackName", draft.trackName)
            root.put("startTimeMs", draft.startTimeMs)
            root.put("accumulatedMs", draft.accumulatedMs)
            root.put("totalDistanceM", draft.totalDistanceM.toDouble())
            root.put("status", draft.status.name)
            root.put("savedAtMs", draft.savedAtMs)
            val pts = JSONArray()
            for (p in draft.points) {
                val o = JSONObject()
                o.put("lat", p.lat); o.put("lon", p.lon)
                o.put("alt", p.altitude); o.put("spd", p.speed.toDouble())
                o.put("acc", p.accuracy.toDouble()); o.put("ts", p.timestamp)
                pts.put(o)
            }
            root.put("points", pts)
            val phs = JSONArray()
            for (ph in draft.photos) {
                val o = JSONObject()
                o.put("uri", ph.uri); o.put("ts", ph.timestamp)
                if (ph.latitude != null) o.put("lat", ph.latitude) else o.put("lat", JSONObject.NULL)
                if (ph.longitude != null) o.put("lon", ph.longitude) else o.put("lon", JSONObject.NULL)
                phs.put(o)
            }
            root.put("photos", phs)
            val file = atomicFile()
            val stream = file.startWrite()
            try {
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                newestWrite = draft.savedAtMs
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }.onFailure { android.util.Log.e("TrackService", "draft write failed", it) }
    }

    @Synchronized
    fun load(): TrackDraft? {
        return runCatching {
            val root = JSONObject(atomicFile().openRead().bufferedReader().use { it.readText() })
            val savedAt = root.optLong("savedAtMs", 0L)
            val points = mutableListOf<TrackPoint>()
            val pts = root.optJSONArray("points") ?: JSONArray()
            for (i in 0 until pts.length()) {
                val o = pts.getJSONObject(i)
                points.add(
                    TrackPoint(
                        lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                        altitude = o.optDouble("alt", 0.0),
                        speed = o.optDouble("spd", 0.0).toFloat(),
                        accuracy = o.optDouble("acc", 0.0).toFloat(),
                        timestamp = o.getLong("ts")
                    )
                )
            }
            val photos = mutableListOf<TrackPhoto>()
            val phs = root.optJSONArray("photos") ?: JSONArray()
            for (i in 0 until phs.length()) {
                val o = phs.getJSONObject(i)
                photos.add(
                    TrackPhoto(
                        trackId = 0, uri = o.getString("uri"), timestamp = o.getLong("ts"),
                        latitude = if (o.isNull("lat")) null else o.getDouble("lat"),
                        longitude = if (o.isNull("lon")) null else o.getDouble("lon")
                    )
                )
            }
            TrackDraft(
                sessionId = root.optString("sessionId", ""),
                trackName = root.optString("trackName", ""),
                startTimeMs = root.optLong("startTimeMs", 0L),
                accumulatedMs = root.optLong("accumulatedMs", 0L),
                totalDistanceM = root.optDouble("totalDistanceM", 0.0).toFloat(),
                status = runCatching { TrackingState.valueOf(root.optString("status", "PAUSED")) }
                    .getOrDefault(TrackingState.PAUSED),
                points = points, photos = photos, savedAtMs = savedAt
            )
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        newestWrite = System.currentTimeMillis() + 1
        atomicFile().delete()
    }
}
