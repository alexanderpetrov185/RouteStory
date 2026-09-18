package com.alexander.routestory.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Light-hearted auto names for finished rides (Sprint 25). */
object TripNames {

    fun generate(distanceMeters: Float, durationMs: Long, endMs: Long): String {
        val minutes = durationMs / 60_000f
        val avgMps = if (durationMs > 0) distanceMeters / (durationMs / 1000f) else 0f
        return when {
            // Almost nothing happened: a step outside / checking the app.
            minutes < 1.5f || distanceMeters < 300f -> pick(SHORT, endMs)
            // Fast for a phone-tracked trip (~ > 30 km/h sustained).
            avgMps > 8.5f -> pick(FAST, endMs)
            // Slower than a slow walk.
            avgMps < 1.0f -> pick(SLOW, endMs)
            else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(endMs))
                .let { "Поездка $it" }
        }
    }

    private fun pick(variant: Array<String>, endMs: Long): String =
        variant[(endMs / 1_000L).toInt().mod(variant.size)]

    private val SHORT = arrayOf("Коротыш", "Вышел подышать", "Вылазка")
    private val FAST = arrayOf("Молния", "Куда так спешил?", "Гонка")
    private val SLOW = arrayOf("Черепаший ход", "Прогулка медитативная", "Шаг за шагом")
}