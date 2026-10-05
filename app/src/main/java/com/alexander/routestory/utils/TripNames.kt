package com.alexander.routestory.utils

import java.util.Calendar

/** Witty auto names for finished rides: duration x pace, travel and tracking lexicon. */
object TripNames {

    /**
     * Sprint 69: every name is ONE coherent nominative phrase - the part of the day plus a
     * continuation that agrees in gender ("Вечерняя прогулка", "Ночной круг"). Taken from
     * the DEVICE time zone (Calendar.getInstance() reads the system zone, DST included).
     */
    fun generate(
        distanceMeters: Float,
        durationMs: Long,
        endMs: Long,
        startMs: Long = 0L
    ): String {
        val minutes = durationMs / 60_000f
        val avgMps = if (durationMs > 0) distanceMeters / (durationMs / 1000f) else 0f
        val bucket = when {
            // Barely a ride: a step outside, or just checking the app.
            minutes < 1.5f || distanceMeters < 300f -> SHORT
            // A proper outing: an hour and a half or more on the move.
            minutes >= 90f -> LONG
            // Fast for a phone-tracked trip (~ > 30 km/h sustained).
            avgMps > 8.5f -> FAST
            // Slower than a slow walk.
            avgMps < 1.0f -> SLOW
            // Easy walking pace with stops to look around.
            avgMps < 2.2f -> WALK
            // A brisk walk or an easy jog.
            avgMps < 4.5f -> JOG
            // On wheels: bike cadence with coasting downhills.
            else -> RIDE
        }
        val hourMs = startMs.takeIf { it > 0L } ?: endMs
        return pick(bucket, hourMs, endMs)
    }

    /** Local hour of [ms] -> the four buckets the user asked for. */
    internal fun partOfDay(ms: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Утренняя"
            in 12..17 -> "Дневная"
            in 18..22 -> "Вечерняя"
            else -> "Ночная"
        }
    }

    private fun pick(bucket: Map<String, Array<String>>, hourMs: Long, endMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = hourMs }
            .get(Calendar.HOUR_OF_DAY)
        val key = when (hour) {
            in 5..11 -> "morning"
            in 12..17 -> "day"
            in 18..22 -> "evening"
            else -> "night"
        }
        val variants = bucket[key] ?: bucket.values.first()
        return variants[(endMs / 1_000L).toInt().mod(variants.size)]
    }

    private val SHORT = mapOf(
        "morning" to arrayOf("Утренняя разведка", "Утренняя прогулка до угла", "Утренняя разминка"),
        "day" to arrayOf("Дневная разведка", "Дневная прогулка до угла", "Дневная разминка"),
        "evening" to arrayOf("Вечерняя разведка", "Вечерняя прогулка до угла", "Вечерняя разминка"),
        "night" to arrayOf("Ночная разведка", "Ночная прогулка до угла", "Ночная разминка")
    )
    private val LONG = mapOf(
        "morning" to arrayOf("Утренняя экспедиция", "Утренняя прогулка с привалом", "Утренний большой круг"),
        "day" to arrayOf("Дневная экспедиция", "Дневной большой круг", "Дневная прогулка с привалом"),
        "evening" to arrayOf("Вечерняя экспедиция", "Вечерний большой круг", "Вечерняя прогулка с привалом"),
        "night" to arrayOf("Ночная экспедиция", "Ночной большой круг", "Ночная прогулка с привалом")
    )
    private val FAST = mapOf(
        "morning" to arrayOf("Утренний спринт", "Утренний скоростной заезд", "Утренняя гонка за кофе"),
        "day" to arrayOf("Дневной спринт", "Дневной скоростной заезд", "Дневная гонка за кофе"),
        "evening" to arrayOf("Вечерний спринт", "Вечерний скоростной заезд", "Вечерняя гонка за кофе"),
        "night" to arrayOf("Ночной спринт", "Ночной скоростной заезд", "Ночная гонка за кофе")
    )
    private val SLOW = mapOf(
        "morning" to arrayOf("Утренняя тихая вылазка", "Утренняя прогулка без спешки", "Утренний неспешный круг"),
        "day" to arrayOf("Дневная тихая вылазка", "Дневная прогулка без спешки", "Дневной неспешный круг"),
        "evening" to arrayOf("Вечерняя тихая вылазка", "Вечерняя прогулка без спешки", "Вечерний неспешный круг"),
        "night" to arrayOf("Ночная тихая вылазка", "Ночная прогулка без спешки", "Ночной неспешный круг")
    )
    private val WALK = mapOf(
        "morning" to arrayOf("Утренняя прогулка", "Утренний круг по району", "Утренний пеший маршрут"),
        "day" to arrayOf("Дневная прогулка", "Дневной круг по району", "Дневной пеший маршрут"),
        "evening" to arrayOf("Вечерняя прогулка", "Вечерний круг по району", "Вечерний пеший маршрут"),
        "night" to arrayOf("Ночная прогулка", "Ночной круг по району", "Ночной пеший маршрут")
    )
    private val JOG = mapOf(
        "morning" to arrayOf("Утренняя пробежка", "Утренний бодрый круг", "Утренняя разминка"),
        "day" to arrayOf("Дневная пробежка", "Дневной бодрый круг", "Дневная разминка"),
        "evening" to arrayOf("Вечерняя пробежка", "Вечерний бодрый круг", "Вечерняя разминка"),
        "night" to arrayOf("Ночная пробежка", "Ночной бодрый круг", "Ночная разминка")
    )
    private val RIDE = mapOf(
        "morning" to arrayOf("Утренняя покатушка", "Утренний велокруг", "Утренний маршрут на колёсах"),
        "day" to arrayOf("Дневная покатушка", "Дневной велокруг", "Дневной маршрут на колёсах"),
        "evening" to arrayOf("Вечерняя покатушка", "Вечерний велокруг", "Вечерний маршрут на колёсах"),
        "night" to arrayOf("Ночная покатушка", "Ночной велокруг", "Ночной маршрут на колёсах")
    )
}