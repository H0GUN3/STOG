package com.stog.app.feature.plan.trip

import com.stog.app.feature.space.TripSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

internal data class TripCalendarDay(
    val date: LocalDate,
    val inDisplayedMonth: Boolean,
    val tripIds: Set<Long>,
)

internal fun monthlyTripCalendar(
    month: YearMonth,
    trips: List<TripSummary>,
): List<TripCalendarDay> {
    val first = month.atDay(1)
    val leadingDays = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val gridStart = first.minusDays(leadingDays.toLong())
    return List(42) { offset ->
        val date = gridStart.plusDays(offset.toLong())
        TripCalendarDay(
            date = date,
            inDisplayedMonth = YearMonth.from(date) == month,
            tripIds = trips.asSequence()
                .filter { it.includes(date) }
                .map(TripSummary::id)
                .toSet(),
        )
    }
}

internal fun tripsInMonth(
    month: YearMonth,
    trips: List<TripSummary>,
): List<TripSummary> {
    val start = month.atDay(1)
    val end = month.atEndOfMonth()
    return trips.filter { trip ->
        val tripStart = trip.plannedStartDate?.let(::parseTripDate) ?: return@filter false
        val tripEnd = trip.plannedEndDate?.let(::parseTripDate) ?: return@filter false
        !tripEnd.isBefore(start) && !tripStart.isAfter(end)
    }
}

internal fun itineraryDayNumber(trip: TripSummary, date: LocalDate): Int? {
    val start = trip.plannedStartDate?.let(::parseTripDate) ?: return null
    val end = trip.plannedEndDate?.let(::parseTripDate) ?: return null
    if (date.isBefore(start) || date.isAfter(end)) return null
    return ChronoUnit.DAYS.between(start, date).toInt() + 1
}

internal fun selectedDateForTrip(trip: TripSummary, preferred: LocalDate): LocalDate? {
    if (itineraryDayNumber(trip, preferred) != null) return preferred
    return trip.plannedStartDate?.let(::parseTripDate)
}

private fun TripSummary.includes(date: LocalDate): Boolean {
    val start = plannedStartDate?.let(::parseTripDate) ?: return false
    val end = plannedEndDate?.let(::parseTripDate) ?: return false
    return !date.isBefore(start) && !date.isAfter(end)
}

private fun parseTripDate(value: String): LocalDate = LocalDate.parse(value)
