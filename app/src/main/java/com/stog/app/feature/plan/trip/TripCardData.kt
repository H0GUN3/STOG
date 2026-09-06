package com.stog.app.feature.plan.trip

import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.tripDurationText
import com.stog.app.feature.space.tripReferenceDateRangeText
import java.time.LocalDate

internal enum class TripListCategory(
    val label: String,
) {
    ALL("전체"),
    ACTIVE("진행 중"),
    UPCOMING("예정"),
    COMPLETED("지난 여행"),
}

internal data class TripCardData(
    val trip: TripSummary,
    val imageRes: Int,
    val imageUrl: String? = null,
    val dateLabel: String = tripDateLabel(trip),
    val memberLabel: String? = null,
    val placeCount: Int = 0,
)

internal fun tripCardData(
    trip: TripSummary,
    imageRes: Int,
    imageUrl: String? = null,
    memberLabel: String? = null,
    placeCount: Int = 0,
): TripCardData = TripCardData(
    trip = trip,
    imageRes = imageRes,
    imageUrl = imageUrl,
    memberLabel = memberLabel,
    placeCount = placeCount,
)

internal fun tripCardsFor(
    category: TripListCategory,
    cards: List<TripCardData>,
    today: LocalDate,
): List<TripCardData> = if (category == TripListCategory.ALL) {
    cards
} else {
    cards.filter { tripCategoryFor(it.trip, today) == category }
}

internal fun tripCategoryFor(trip: TripSummary, today: LocalDate): TripListCategory {
    val start = trip.plannedStartDate?.let(::parseTripDate) ?: return TripListCategory.ALL
    val end = trip.plannedEndDate?.let(::parseTripDate) ?: return TripListCategory.ALL
    return when {
        today.isBefore(start) -> TripListCategory.UPCOMING
        today.isAfter(end) -> TripListCategory.COMPLETED
        else -> TripListCategory.ACTIVE
    }
}

private fun parseTripDate(value: String): LocalDate = LocalDate.parse(value)

private fun tripDateLabel(trip: TripSummary): String {
    if (trip.plannedStartDate == null || trip.plannedEndDate == null) {
        return "날짜 미정"
    }
    return "${tripReferenceDateRangeText(trip)} · ${tripDurationText(trip)}"
}
