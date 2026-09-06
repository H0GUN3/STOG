package com.stog.app.feature.home

import com.stog.app.feature.space.TripSummary

internal fun isHomeTripVisible(trip: TripSummary): Boolean =
    trip.mode == "active" || trip.mode == "dormant"

internal fun homeTripStatus(mode: String): String = when (mode.lowercase()) {
    "active" -> "진행 중"
    "dormant" -> "예정"
    "ended" -> "지난 여행"
    else -> "상태 확인 필요"
}

internal fun homeTripDateRange(trip: TripSummary): String {
    val start = trip.plannedStartDate?.let(::homeShortDate)
    val end = trip.plannedEndDate?.let(::homeShortDate)
    return if (start == null || end == null) "날짜 미정" else "$start - $end"
}

internal fun homeEventDateRange(startsOn: String, endsOn: String): String {
    val start = homeShortDate(startsOn)
    val end = homeShortDate(endsOn)
    return "$start - $end"
}

private fun homeShortDate(value: String): String {
    val parts = value.split("-")
    return if (parts.size == 3) "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}" else value
}
