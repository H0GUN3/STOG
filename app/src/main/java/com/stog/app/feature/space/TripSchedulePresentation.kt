package com.stog.app.feature.space

import com.stog.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class TripRangeEdges(
    val start: Boolean,
    val end: Boolean,
)

internal fun tripRangeEdges(
    days: List<com.stog.app.feature.plan.trip.TripCalendarDay>,
    index: Int,
): TripRangeEdges {
    val current = days.getOrNull(index)?.tripIds.orEmpty()
    if (current.isEmpty()) return TripRangeEdges(start = false, end = false)
    val previous = days.getOrNull(index - 1)?.tripIds.orEmpty()
    val next = days.getOrNull(index + 1)?.tripIds.orEmpty()
    return TripRangeEdges(
        start = current.none(previous::contains),
        end = current.none(next::contains),
    )
}

internal fun tripDateRangeText(trip: TripSummary): String {
    val start = trip.plannedStartDate?.let(::parseDate)
    val end = trip.plannedEndDate?.let(::parseDate)
    return if (start == null || end == null) {
        "날짜 미정"
    } else {
        "${formatDate(start)} - ${formatDate(end)}"
    }
}

internal fun tripDateText(value: String): String =
    parseDate(value)?.let(::formatDate) ?: "날짜 미정"

internal fun tripDurationText(trip: TripSummary): String {
    return tripDurationText(trip.plannedStartDate, trip.plannedEndDate)
}

internal fun tripDurationText(startValue: String?, endValue: String?): String {
    val start = startValue?.let(::parseDate)
    val end = endValue?.let(::parseDate)
    val nights = if (start == null || end == null) {
        return "기간 미정"
    } else {
        ChronoUnit.DAYS.between(start, end)
    }
    return "${nights}박 ${nights + 1}일"
}

internal fun tripStageLabel(mode: String): String = when (mode.lowercase(Locale.ROOT)) {
    "active" -> "여행 중"
    "dormant" -> "예정"
    "ended" -> "완료"
    else -> "상태 확인 필요"
}

internal fun tripDestinationText(trip: TripSummary): String =
    trip.title
        .trim()
        .removeSuffix(" 여행")
        .removeSuffix(" 나들이")
        .removeSuffix(" 주말")
        .substringBefore(' ')
        .ifBlank { "여행" }

internal fun defaultTripImageResource(): Int = R.drawable.stog_travel_alley

internal fun tripImageResource(title: String): Int = when {
    title.contains("제주") -> R.drawable.trip_jeju
    title.contains("전주") -> R.drawable.trip_jeonju
    title.contains("부산") -> R.drawable.trip_busan
    title.contains("남원") -> R.drawable.trip_namwon
    title.contains("군산") -> R.drawable.stog_travel_record
    else -> defaultTripImageResource()
}

internal fun itineraryImageResource(title: String, category: String?): Int = when {
    title.contains("공항") || category == "transport" -> R.drawable.trip_detail_airport
    title.contains("숙소") || category == "lodging" -> R.drawable.trip_detail_lodging
    title.contains("카페") || category == "cafe" -> R.drawable.trip_detail_cafe
    title.contains("해변") || category == "beach" -> R.drawable.trip_detail_beach
    title.contains("흑돼지") || category == "restaurant" -> R.drawable.trip_detail_bbq
    else -> R.drawable.trip_jeju
}

internal fun tripReferenceDateRangeText(trip: TripSummary): String {
    val start = trip.plannedStartDate?.let(::parseDate)
    val end = trip.plannedEndDate?.let(::parseDate)
    return if (start == null || end == null) {
        "날짜 미정"
    } else {
        "${formatReferenceDate(start)} - ${formatReferenceDate(end)}"
    }
}

internal fun tripDayDateText(trip: TripSummary, dayNumber: Int): String {
    val start = trip.plannedStartDate?.let(::parseDate) ?: return "날짜 미정"
    return formatReferenceDate(start.plusDays((dayNumber - 1).toLong()))
}

internal fun plannedArrivalText(value: String?): String =
    value?.substringAfter('T', value)?.take(5).orEmpty().ifBlank { "--:--" }

private fun parseDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value)
}.getOrNull()

private fun formatDate(value: LocalDate): String =
    value.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT))

private fun formatReferenceDate(value: LocalDate): String =
    "${value.monthValue}/${value.dayOfMonth} (${
        value.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN)
    })"
