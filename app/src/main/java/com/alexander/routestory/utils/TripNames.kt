package com.alexander.routestory.utils

/** Witty auto names for finished rides: duration x pace, travel and tracking lexicon. */
object TripNames {

    fun generate(distanceMeters: Float, durationMs: Long, endMs: Long): String {
        val minutes = durationMs / 60_000f
        val avgMps = if (durationMs > 0) distanceMeters / (durationMs / 1000f) else 0f
        return when {
            // Barely a ride: a step outside, or just checking the app.
            minutes < 1.5f || distanceMeters < 300f -> pick(SHORT, endMs)
            // A proper outing: an hour and a half or more on the move.
            minutes >= 90f -> pick(LONG, endMs)
            // Fast for a phone-tracked trip (~ > 30 km/h sustained).
            avgMps > 8.5f -> pick(FAST, endMs)
            // Slower than a slow walk.
            avgMps < 1.0f -> pick(SLOW, endMs)
            // Easy walking pace with stops to look around.
            avgMps < 2.2f -> pick(WALK, endMs)
            // A brisk walk or an easy jog.
            avgMps < 4.5f -> pick(JOG, endMs)
            // On wheels: bike cadence with coasting downhills.
            else -> pick(RIDE, endMs)
        }
    }

    private fun pick(variant: Array<String>, endMs: Long): String =
        variant[(endMs / 1_000L).toInt().mod(variant.size)]

    private val SHORT = arrayOf("Выход на разведку", "Прогулка до угла", "Тест компаса")
    private val LONG = arrayOf("Воскресная экспедиция", "Большой круг", "Маршрут с привалом")
    private val FAST = arrayOf("Спринт до кофе", "Маршрут с ветерком", "Скоростной рейс")
    private val SLOW = arrayOf("Тихая вылазка", "Маршрут без спешки", "Прогулка вразвалку")
    private val WALK = arrayOf("Пеший трек", "Круг по району", "Маршрут выходного дня")
    private val JOG = arrayOf("Бодрый круг", "Пробежка по маршруту", "Утренний темп")
    private val RIDE = arrayOf("Велокаденс", "Покатушка", "Маршрут на колёсах")
}