package com.stog.app.feature.space

import com.stog.app.feature.plan.trip.TripCalendarDay
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class TripSchedulePresentationTest {
    @Test
    fun tripCardUsesOneDateStyleAndComputesNightsAndDays() {
        val trip = TripSummary(
            id = 1,
            title = "전주 여행",
            activityType = "tour",
            mode = "active",
            visibility = "private",
            plannedStartDate = "2026-08-25",
            plannedEndDate = "2026-08-28",
        )

        assertEquals("2026.08.25 - 2026.08.28", tripDateRangeText(trip))
        assertEquals("3박 4일", tripDurationText(trip))
    }

    @Test
    fun calendarRangeRoundsOnlyAtTheTripBoundaries() {
        val month = YearMonth.of(2026, 8)
        val days = listOf(
            TripCalendarDay(LocalDate.of(2026, 8, 24), true, setOf(2)),
            TripCalendarDay(LocalDate.of(2026, 8, 25), true, setOf(1)),
            TripCalendarDay(LocalDate.of(2026, 8, 26), true, setOf(1)),
            TripCalendarDay(LocalDate.of(2026, 8, 27), true, setOf(1)),
            TripCalendarDay(LocalDate.of(2026, 8, 28), true, setOf(1)),
            TripCalendarDay(LocalDate.of(2026, 8, 29), true, emptySet()),
        )

        assertEquals(
            TripRangeEdges(start = true, end = false),
            tripRangeEdges(days, index = 1),
        )
        assertEquals(
            TripRangeEdges(start = false, end = true),
            tripRangeEdges(days, index = 4),
        )
        assertEquals(
            TripRangeEdges(start = true, end = true),
            tripRangeEdges(days, index = 0),
        )
        assertEquals(month, YearMonth.from(days[1].date))
    }
}
