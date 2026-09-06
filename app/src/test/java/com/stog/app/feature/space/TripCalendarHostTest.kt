package com.stog.app.feature.space

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stog.app.feature.plan.trip.monthlyTripCalendar
import com.stog.app.ui.theme.STOGTheme
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TripCalendarHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun calendarShowsMonthTripMarkerAndReportsSelectedDate() {
        val month = YearMonth.of(2026, 9)
        val trip = TripSummary(7, "여행", "tour", "dormant", "private", "2026-09-15", "2026-09-16")
        var selected: LocalDate? = null

        composeRule.setContent {
            STOGTheme {
                MonthlyTripCalendar(
                    month = month,
                    days = monthlyTripCalendar(month, listOf(trip)),
                    selectedDate = LocalDate.of(2026, 9, 1),
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onDateSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("2026년 9월").assertExists()
        composeRule.onNodeWithText("15").performClick()
        composeRule.runOnIdle { assertEquals(LocalDate.of(2026, 9, 15), selected) }
    }
}
