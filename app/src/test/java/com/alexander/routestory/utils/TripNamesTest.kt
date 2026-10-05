package com.alexander.routestory.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Sprint 69: ride names are coherent nominative phrases with agreeing gender. */
class TripNamesTest {

    /** Milliseconds for a given LOCAL hour today (so the assertion never depends on the zone). */
    private fun localHour(hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun partOfDayBoundaries() {
        assertEquals("Утренняя", TripNames.partOfDay(localHour(5)))
        assertEquals("Утренняя", TripNames.partOfDay(localHour(11)))
        assertEquals("Дневная", TripNames.partOfDay(localHour(12)))
        assertEquals("Дневная", TripNames.partOfDay(localHour(17)))
        assertEquals("Вечерняя", TripNames.partOfDay(localHour(18)))
        assertEquals("Вечерняя", TripNames.partOfDay(localHour(22)))
        assertEquals("Ночная", TripNames.partOfDay(localHour(23)))
        assertEquals("Ночная", TripNames.partOfDay(localHour(4)))
    }

    @Test fun nameCarriesTheLocalPartOfDayPrefix() {
        val name = TripNames.generate(
            distanceMeters = 5_000f,
            durationMs = 20 * 60_000L,
            endMs = localHour(7),
            startMs = localHour(7)
        )
        assertTrue(name, name.startsWith("Утренняя ") || name.startsWith("Утренний "))
    }

    @Test fun eveningRideIsNotCalledMorning() {
        val name = TripNames.generate(
            distanceMeters = 4_000f,
            durationMs = 30 * 60_000L,
            endMs = localHour(21),
            startMs = localHour(21)
        )
        assertTrue(name, name.startsWith("Вечерняя ") || name.startsWith("Вечерний "))
    }

    @Test fun startHourWinsForThePrefix() {
        // Ride started at 23:50 and stopped at 00:10: it is a night ride.
        val name = TripNames.generate(
            distanceMeters = 4_000f,
            durationMs = 20 * 60_000L,
            endMs = localHour(0),
            startMs = localHour(23)
        )
        assertTrue(name, name.startsWith("Ночная ") || name.startsWith("Ночной "))
    }

    @Test fun nameIsACoherentPhraseWithoutMiddleDot() {
        // Old bug: "Вечерняя · Тест компаса" - time prefix + unrelated meme.
        val cases = listOf(
            Triple(200f, 60_000L, 8),
            Triple(5_000f, 20 * 60_000L, 14),
            Triple(4_000f, 30 * 60_000L, 21),
            Triple(20_000f, 100 * 60_000L, 2)
        )
        for ((dist, dur, hour) in cases) {
            val name = TripNames.generate(
                distanceMeters = dist,
                durationMs = dur,
                endMs = localHour(hour) + 1_234_000L,
                startMs = localHour(hour)
            )
            assertTrue(name, !name.contains("·"))
            assertTrue(name, !name.contains("Тест компаса"))
            assertTrue(name, name.trim().split(Regex("\\s+")).size >= 2)
        }
    }
}