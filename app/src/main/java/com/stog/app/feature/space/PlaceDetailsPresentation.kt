package com.stog.app.feature.space

import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal fun placeRatingSummary(
    rating: Double?,
    userRatingCount: Int?,
): String? {
    val value = rating ?: return null
    val ratingText = String.format(Locale.KOREA, "%.1f", value)
    return userRatingCount?.let { "$ratingText · 리뷰 $it" } ?: ratingText
}

internal fun placeOpeningSummary(
    businessStatus: String?,
    openNow: Boolean?,
    nextCloseTime: String?,
): String? = when (businessStatus) {
    "CLOSED_PERMANENTLY" -> "폐업"
    "CLOSED_TEMPORARILY" -> "임시 휴업"
    else -> when (openNow) {
        true -> formatCloseTime(nextCloseTime)?.let { "영업 중 · ${it}까지" } ?: "영업 중"
        false -> "영업 종료"
        null -> null
    }
}

internal fun formatPlaceDistance(distanceMeters: Float?): String? {
    val distance = distanceMeters?.takeIf { it.isFinite() && it >= 0f } ?: return null
    return if (distance < 1_000f) {
        "${distance.roundToInt()}m"
    } else {
        String.format(Locale.KOREA, "%.1fkm", distance / 1_000f)
            .replace(".0km", "km")
    }
}

internal fun todayOpeningHours(
    regularOpeningHours: List<String>,
    dayOfWeek: DayOfWeek = OffsetDateTime.now().dayOfWeek,
): String? {
    val prefix = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        DayOfWeek.SUNDAY -> "일요일"
    }
    return regularOpeningHours.firstOrNull { it.startsWith(prefix) }
        ?.substringAfter(':', missingDelimiterValue = "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

private fun formatCloseTime(value: String?): String? =
    value?.let {
        runCatching {
            OffsetDateTime.parse(it)
                .toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrNull()
    }
