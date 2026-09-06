package com.stog.app.feature.plan.trip

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class TripDateRange(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    val isComplete: Boolean
        get() = start != null && end != null
}

internal fun selectTripDate(range: TripDateRange, date: LocalDate): TripDateRange {
    val start = range.start
    if (start == null || range.isComplete) return TripDateRange(start = date)
    return if (date.isBefore(start)) {
        TripDateRange(start = date, end = start)
    } else {
        TripDateRange(start = start, end = date)
    }
}

internal fun tripDatePickerDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val leadingDays = (first.dayOfWeek.value % DayOfWeek.SUNDAY.value)
    val gridStart = first.minusDays(leadingDays.toLong())
    return List(42) { offset -> gridStart.plusDays(offset.toLong()) }
}

internal fun tripTitleError(title: String): String? =
    "여행 이름을 입력해주세요".takeIf { title.isBlank() }

internal fun tripDateRangeSummary(range: TripDateRange): String {
    val start = requireNotNull(range.start)
    val end = requireNotNull(range.end)
    return "${formatTripDate(start)} - ${formatTripDate(end)} · ${tripNightsText(start, end)}"
}

internal fun formatTripDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))

internal fun tripNightsText(start: LocalDate, end: LocalDate): String {
    val nights = ChronoUnit.DAYS.between(start, end)
    return "${nights}박 ${nights + 1}일"
}
