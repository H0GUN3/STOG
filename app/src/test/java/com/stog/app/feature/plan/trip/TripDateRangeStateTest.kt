package com.stog.app.feature.plan.trip

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDateRangeStateTest {
    @Test
    fun firstTapSetsStartAndSecondTapCompletesRange() {
        val start = LocalDate.of(2026, 8, 27)

        val first = selectTripDate(TripDateRange(), start)
        val completed = selectTripDate(first, start.plusDays(1))

        assertEquals(start, first.start)
        assertNull(first.end)
        assertEquals(start, completed.start)
        assertEquals(start.plusDays(1), completed.end)
    }

    @Test
    fun earlierSecondTapKeepsChronologicalRange() {
        val range = selectTripDate(
            TripDateRange(start = LocalDate.of(2026, 8, 28)),
            LocalDate.of(2026, 8, 27),
        )

        assertEquals(LocalDate.of(2026, 8, 27), range.start)
        assertEquals(LocalDate.of(2026, 8, 28), range.end)
    }

    @Test
    fun completedRangeStartsOverWhenUserTapsAnotherDate() {
        val range = selectTripDate(
            TripDateRange(
                start = LocalDate.of(2026, 8, 27),
                end = LocalDate.of(2026, 8, 28),
            ),
            LocalDate.of(2026, 9, 1),
        )

        assertEquals(TripDateRange(start = LocalDate.of(2026, 9, 1)), range)
    }

    @Test
    fun datePickerGridStartsOnSundayAndAlwaysHasSixWeeks() {
        val days = tripDatePickerDays(YearMonth.of(2026, 8))

        assertEquals(42, days.size)
        assertEquals(LocalDate.of(2026, 7, 26), days.first())
        assertEquals(LocalDate.of(2026, 8, 1), days[6])
        assertEquals(LocalDate.of(2026, 9, 5), days.last())
    }

    @Test
    fun rangeSummaryUsesKoreanMonthDayAndNightCount() {
        val range = TripDateRange(
            start = LocalDate.of(2026, 8, 27),
            end = LocalDate.of(2026, 8, 28),
        )

        assertEquals("8월 27일 - 8월 28일 · 1박 2일", tripDateRangeSummary(range))
    }

    @Test
    fun blankTitleReturnsInlineValidationMessage() {
        assertEquals("여행 이름을 입력해주세요", tripTitleError("  "))
        assertTrue(tripTitleError("전주 여행").isNullOrBlank())
    }
}
