package com.stog.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class SetLogPlaceState(val machineValue: String) {
    MATCHED("matched"),
    NO_MATCH("no_match"),
    UNKNOWN("unknown"),
}

internal enum class SetLogCaptureTimeState(val machineValue: String) {
    KNOWN("known"),
    UNKNOWN("unknown"),
}

internal enum class SetLogReadPublicationState(val machineValue: String) {
    PRIVATE("private"),
    GROUP("group"),
    TRIP_NOT_PUBLIC("trip_not_public"),
    MODERATION_PENDING("moderation_pending"),
    MODERATION_BLOCKED("moderation_blocked"),
    REVOKED("revoked"),
    PUBLIC("public"),
    UNKNOWN("unknown"),
}

internal data class SetLogCaptureTime(
    val state: SetLogCaptureTimeState,
    val label: String,
)

internal data class SetLogReadbackMetadata(
    val placeId: Long?,
    val placeNameSnapshot: String?,
    val placeState: SetLogPlaceState,
    val placeLabel: String,
    val note: String?,
    val captureTime: SetLogCaptureTime,
    val visibility: PhotoVisibility?,
    val visibilityLabel: String,
    val moderationStatus: String?,
    val moderationLabel: String,
    val publicationStatus: String?,
    val publicationState: SetLogReadPublicationState,
    val publicationLabel: String,
    val accuracyMeters: Double?,
    val accuracyLabel: String?,
) {
    val stateDescription: String = listOf(
        "place=${placeState.machineValue}",
        "note=${if (note == null) "absent" else "present"}",
        "time=${captureTime.state.machineValue}",
        "visibility=${visibility?.wireValue ?: "unknown"}",
        "moderation=${moderationStatus ?: "unknown"}",
        "publication=${publicationState.machineValue}",
        "accuracy=${if (accuracyMeters == null) "absent" else "present"}",
    ).joinToString(";")

    val mapSnippet: String = listOfNotNull(
        note,
        captureTime.label,
        visibilityLabel,
        publicationLabel,
    ).joinToString(" · ")
}

internal fun setLogReadbackMetadata(
    placeId: Long?,
    placeNameSnapshot: String?,
    placeResolutionStatus: String?,
    note: String?,
    takenAt: String?,
    visibility: PhotoVisibility?,
    moderationStatus: String?,
    publicationStatus: String?,
    accuracyMeters: Double?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): SetLogReadbackMetadata {
    val snapshot = placeNameSnapshot?.takeIf(String::isNotBlank)
    val placeState = when {
        snapshot != null -> SetLogPlaceState.MATCHED
        placeResolutionStatus == "no_match" -> SetLogPlaceState.NO_MATCH
        else -> SetLogPlaceState.UNKNOWN
    }
    val publicationState = when (publicationStatus) {
        "private" -> SetLogReadPublicationState.PRIVATE
        "group" -> SetLogReadPublicationState.GROUP
        "trip_not_public" -> SetLogReadPublicationState.TRIP_NOT_PUBLIC
        "moderation_pending" -> SetLogReadPublicationState.MODERATION_PENDING
        "moderation_blocked" -> SetLogReadPublicationState.MODERATION_BLOCKED
        "revoked" -> SetLogReadPublicationState.REVOKED
        "public" -> SetLogReadPublicationState.PUBLIC
        else -> SetLogReadPublicationState.UNKNOWN
    }
    val permittedAccuracy = setLogAccuracyMeters(accuracyMeters)
    return SetLogReadbackMetadata(
        placeId = placeId,
        placeNameSnapshot = snapshot,
        placeState = placeState,
        placeLabel = when (placeState) {
            SetLogPlaceState.MATCHED -> checkNotNull(snapshot)
            SetLogPlaceState.NO_MATCH -> "일치하는 장소 없음"
            SetLogPlaceState.UNKNOWN -> "장소 정보 알 수 없음"
        },
        note = note?.takeIf(String::isNotBlank),
        captureTime = formatSetLogCaptureInstant(takenAt, zoneId, locale),
        visibility = visibility,
        visibilityLabel = when (visibility) {
            PhotoVisibility.PRIVATE -> "나만 보기"
            PhotoVisibility.GROUP -> "그룹 여행에서 보기"
            PhotoVisibility.PUBLIC -> "전체 공개 의도"
            null -> "공개 범위 알 수 없음"
        },
        moderationStatus = moderationStatus,
        moderationLabel = when (moderationStatus) {
            "pending" -> "검토 대기"
            "approved" -> "검토 승인"
            "blocked" -> "검토 차단"
            else -> "검토 상태 알 수 없음"
        },
        publicationStatus = publicationStatus,
        publicationState = publicationState,
        publicationLabel = when (publicationState) {
            SetLogReadPublicationState.PRIVATE -> "공개되지 않음"
            SetLogReadPublicationState.GROUP -> "그룹 범위 · 공개되지 않음"
            SetLogReadPublicationState.TRIP_NOT_PUBLIC -> "여행이 비공개라 공개되지 않음"
            SetLogReadPublicationState.MODERATION_PENDING -> "검토 대기 · 아직 공개되지 않음"
            SetLogReadPublicationState.MODERATION_BLOCKED -> "검토 차단 · 공개되지 않음"
            SetLogReadPublicationState.REVOKED -> "공개 동의 철회 · 공개되지 않음"
            SetLogReadPublicationState.PUBLIC -> "공개 중"
            SetLogReadPublicationState.UNKNOWN -> "게시 상태 알 수 없음"
        },
        accuracyMeters = permittedAccuracy,
        accuracyLabel = permittedAccuracy?.let { value ->
            val formatter = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 1
                isGroupingUsed = false
            }
            "위치 정확도 ±${formatter.format(value)}m"
        },
    )
}

internal fun formatSetLogCaptureInstant(
    takenAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): SetLogCaptureTime {
    val instant = takenAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return SetLogCaptureTime(SetLogCaptureTimeState.UNKNOWN, "촬영 시각 알 수 없음")
    val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", locale).withZone(zoneId)
    return SetLogCaptureTime(SetLogCaptureTimeState.KNOWN, "촬영 ${formatter.format(instant)}")
}

internal fun setLogInstant(value: String?): String? = value
    ?.takeIf(String::isNotBlank)
    ?.takeIf { runCatching { Instant.parse(it) }.isSuccess }

internal fun setLogPlaceResolutionStatus(value: String?): String? =
    value?.takeIf { it == "matched" || it == "no_match" }

internal fun setLogModerationStatus(value: String?): String? =
    value?.takeIf { it == "pending" || it == "approved" || it == "blocked" }

internal fun setLogPublicationStatus(value: String?): String? = value?.takeIf {
    it in setOf(
        "private",
        "group",
        "trip_not_public",
        "moderation_pending",
        "moderation_blocked",
        "revoked",
        "public",
    )
}

internal fun setLogLocationProvenance(value: String?): String? =
    value?.takeIf { it == "camera_foreground" || it == "gallery_exif" }

internal fun setLogAccuracyMeters(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 }

internal fun RemotePhoto.readbackMetadata(
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): SetLogReadbackMetadata = setLogReadbackMetadata(
    placeId = placeId,
    placeNameSnapshot = placeName,
    placeResolutionStatus = placeResolutionStatus,
    note = caption,
    takenAt = takenAt,
    visibility = visibility,
    moderationStatus = moderationStatus,
    publicationStatus = publicationStatus,
    accuracyMeters = accuracyMeters,
    zoneId = zoneId,
    locale = locale,
)

internal fun ArchivePhoto.readbackMetadata(
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): SetLogReadbackMetadata = setLogReadbackMetadata(
    placeId = placeId,
    placeNameSnapshot = placeName,
    placeResolutionStatus = placeResolutionStatus,
    note = caption,
    takenAt = takenAt,
    visibility = visibility,
    moderationStatus = moderationStatus,
    publicationStatus = publicationStatus,
    accuracyMeters = accuracyMeters,
    zoneId = zoneId,
    locale = locale,
)

@Composable
internal fun SetLogReadbackMetadata(
    metadata: SetLogReadbackMetadata,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag(testTag)
            .semantics(mergeDescendants = true) {
                stateDescription = metadata.stateDescription
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = metadata.placeLabel,
            style = MaterialTheme.typography.titleSmall,
            color = StogInk,
        )
        metadata.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = StogInk,
            )
        }
        Text(
            text = metadata.captureTime.label,
            style = MaterialTheme.typography.bodySmall,
            color = StogMuted,
        )
        Text(
            text = "${metadata.visibilityLabel} · ${metadata.publicationLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = StogMuted,
        )
        Text(
            text = metadata.moderationLabel,
            style = MaterialTheme.typography.bodySmall,
            color = StogMuted,
        )
        metadata.accuracyLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = StogMuted,
            )
        }
    }
}
