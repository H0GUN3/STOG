package com.stog.app.feature.plan.trip

import com.stog.app.feature.space.TripSummary
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCalendarStateTest {
    @Test
    fun monthProjectionIsMondayFirstFixedSixWeeksAndMarksInclusiveTripRange() {
        val trip = trip(7, "2026-08-31", "2026-09-02")

        val days = monthlyTripCalendar(YearMonth.of(2026, 9), listOf(trip))

        assertEquals(42, days.size)
        assertEquals(LocalDate.of(2026, 8, 31), days.first().date)
        assertFalse(days.first().inDisplayedMonth)
        assertEquals(setOf(7L), days.first().tripIds)
        assertEquals(setOf(7L), days.single { it.date == LocalDate.of(2026, 9, 2) }.tripIds)
        assertTrue(days.single { it.date == LocalDate.of(2026, 9, 3) }.tripIds.isEmpty())
    }

    @Test
    fun leapMonthAndCrossMonthFilteringUseRealCalendarBounds() {
        val crossing = trip(1, "2024-02-29", "2024-03-02")
        val march = trip(2, "2024-03-31", "2024-04-01")
        val april = trip(3, "2024-04-02", "2024-04-03")

        assertEquals(
            listOf(1L, 2L),
            tripsInMonth(YearMonth.of(2024, 3), listOf(crossing, march, april)).map { it.id },
        )
    }

    @Test
    fun selectedCalendarDateMapsToOneBasedItineraryDay() {
        val trip = trip(4, "2026-09-10", "2026-09-12")

        assertEquals(1, itineraryDayNumber(trip, LocalDate.of(2026, 9, 10)))
        assertEquals(3, itineraryDayNumber(trip, LocalDate.of(2026, 9, 12)))
        assertNull(itineraryDayNumber(trip, LocalDate.of(2026, 9, 13)))
        assertEquals(
            LocalDate.of(2026, 9, 10),
            selectedDateForTrip(trip, LocalDate.of(2026, 8, 1)),
        )
    }

    private fun trip(id: Long, start: String, end: String) = TripSummary(
        id = id,
        title = "trip-$id",
        activityType = "tour",
        mode = "dormant",
        visibility = "private",
        plannedStartDate = start,
        plannedEndDate = end,
    )
}
