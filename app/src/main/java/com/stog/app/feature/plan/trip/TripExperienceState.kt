package com.stog.app.feature.plan.trip

internal enum class TripStage {
    UPCOMING,
    ACTIVE,
    COMPLETED,
}

internal enum class HomeTravelState {
    EMPTY,
    UPCOMING,
    ACTIVE,
    COMPLETED,
}

internal data class TripCardUi(
    val id: Long,
    val title: String,
    val stage: TripStage,
    val dateRange: String,
    val statusLabel: String,
    val members: List<String> = emptyList(),
    val dayCount: Int = 1,
)

internal val HOME_TRIP_ITEMS: List<TripCardUi> = emptyList()

internal data class BasketPlaceUi(
    val id: Long,
    val title: String,
    val category: String,
    val addedBy: String,
)

internal data class ItineraryStopUi(
    val id: Long,
    val time: String,
    val title: String,
    val category: String,
    val visitDuration: String,
    val travelToNext: String? = null,
    val completed: Boolean = false,
)

internal fun homeTrip(trips: List<TripCardUi>): TripCardUi? =
    trips.minByOrNull { trip ->
        when (trip.stage) {
            TripStage.ACTIVE -> 0
            TripStage.UPCOMING -> 1
            TripStage.COMPLETED -> 2
        }
    }

internal fun toggleBasketSelection(selected: Set<Long>, placeId: Long): Set<Long> =
    if (placeId in selected) selected - placeId else selected + placeId

internal data class JourneyItems(
    val remaining: List<ItineraryStopUi>,
    val completed: List<ItineraryStopUi>,
)

internal fun journeyItems(items: List<ItineraryStopUi>): JourneyItems = JourneyItems(
    remaining = items.filterNot(ItineraryStopUi::completed),
    completed = items.filter(ItineraryStopUi::completed),
)

internal fun journeyStateLabel(mode: String): String = when (mode.lowercase()) {
    "active" -> "여행 기록 중"
    "dormant" -> "기록 대기 중"
    "ended" -> "종료된 여행"
    else -> "상태 확인 필요"
}

internal fun journeyFailureMessage(statusCode: Int, code: String? = null): String = when {
    statusCode == 401 -> "로그인이 만료되었어요. 다시 로그인해 주세요."
    statusCode == 403 -> "이 여행의 참여자만 확인하거나 수정할 수 있어요."
    code == "VISIT_AFTER_TRIP_END" -> "여행 종료 뒤에 시작된 방문 기록은 반영할 수 없어요."
    code == "LATE_VISIT_GRACE_EXPIRED" -> "늦게 도착한 방문 기록의 반영 시간이 지났어요."
    statusCode == 409 -> "다른 참여자가 먼저 변경했어요. 최신 상태를 다시 불러와 주세요."
    else -> "여행 요청을 처리하지 못했어요."
}

