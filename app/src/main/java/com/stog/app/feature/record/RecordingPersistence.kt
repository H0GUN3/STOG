package com.stog.app.feature.record

import com.stog.app.core.database.LocationClassificationState
import com.stog.app.core.database.LocationObservationEntity
import com.stog.app.core.database.MemberCollectionStateEntity
import com.stog.app.core.database.PersistedCollectorState
import com.stog.app.core.database.PersistedPermissionState
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.core.database.VisitStatus
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class RecordingEngine(
    private val database: StogDatabase,
    private val scheduleVisits: (String, String) -> Unit,
    private val tuning: RecordingTuning,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun handle(state: MemberRecordingState, event: RecordingEvent): RecordingResult {
        val result = reduceRecording(state, event, tuning)
        if (event is RecordingEvent.Location) {
            persistLocation(state, event, result)
        } else {
            database.memberCollectionStateDao().save(result.state.toEntity(now()))
        }
        return result
    }

    private fun persistLocation(
        previous: MemberRecordingState,
        event: RecordingEvent.Location,
        result: RecordingResult,
    ) {
        val observationId = UUID.randomUUID().toString()
        val enqueue = result.effects.filterIsInstance<RecordingEffect.EnqueueVisit>().singleOrNull()
        val visit = enqueue?.toVisit(previous, observationId, now())
        database.recordingPersistenceDao().persistObservationResult(
            observation = LocationObservationEntity(
                observationId = observationId,
                accountId = previous.accountId,
                tripId = previous.tripId,
                userId = previous.userId,
                cellId = event.cell,
                lat = event.lat,
                lng = event.lng,
                observedAt = event.observedAt,
                classificationState = LocationClassificationState.PENDING,
            ),
            state = result.state.toEntity(now()),
            visit = visit,
        )
        if (visit != null) scheduleVisits(previous.accountId, previous.tripId)
    }
}

internal fun MemberRecordingState.toEntity(updatedAt: Long) = MemberCollectionStateEntity(
    accountId = accountId,
    tripId = tripId,
    userId = userId,
    collectorState = PersistedCollectorState.valueOf(collectorState.name),
    permissionState = PersistedPermissionState.valueOf(permissionState.name),
    modeVersion = modeVersion,
    tripEndAt = tripEndAt,
    batteryLow = batteryLow,
    activeCell = activeCell,
    activeLat = activeLat,
    activeLng = activeLng,
    enteredAt = enteredAt,
    candidateCell = candidateCell,
    candidateCount = candidateCount,
    lastAcceptedLat = lastAcceptedLat,
    lastAcceptedLng = lastAcceptedLng,
    lastAcceptedAt = lastAcceptedAt,
    lastTransitionAt = lastTransitionAt,
    updatedAt = updatedAt,
)

internal fun MemberCollectionStateEntity.toRecordingState() = MemberRecordingState(
    accountId = accountId,
    tripId = tripId,
    userId = userId,
    collectorState = CollectorState.valueOf(collectorState.name),
    permissionState = PermissionState.valueOf(permissionState.name),
    modeVersion = modeVersion,
    tripEndAt = tripEndAt,
    batteryLow = batteryLow,
    activeCell = activeCell,
    activeLat = activeLat,
    activeLng = activeLng,
    enteredAt = enteredAt,
    candidateCell = candidateCell,
    candidateCount = candidateCount,
    lastAcceptedLat = lastAcceptedLat,
    lastAcceptedLng = lastAcceptedLng,
    lastAcceptedAt = lastAcceptedAt,
    lastTransitionAt = lastTransitionAt,
)

private fun RecordingEffect.EnqueueVisit.toVisit(
    state: MemberRecordingState,
    observationId: String,
    createdAt: Long,
): VisitOutboxEntity {
    val clientVisitId = UUID.randomUUID().toString()
    val status = if (visited) VisitStatus.VISITED else VisitStatus.PASSED
    val fingerprint = visitPayloadFingerprint(cell, lat, lng, enteredAt, leftAt, status, false)
    return VisitOutboxEntity(
        observationId = observationId,
        accountId = state.accountId,
        tripId = state.tripId,
        userId = state.userId,
        clientVisitId = clientVisitId,
        payloadFingerprint = fingerprint,
        cellId = cell,
        lat = lat,
        lng = lng,
        enteredAt = enteredAt,
        leftAt = leftAt,
        status = status,
        isInterpolated = false,
        createdAt = createdAt,
    )
}

internal fun visitPayloadFingerprint(
    cell: Long,
    lat: Double,
    lng: Double,
    enteredAt: Long,
    leftAt: Long,
    status: VisitStatus,
    isInterpolated: Boolean,
): String {
    val canonical = buildString {
        append("cell_id=").append(cell.toString(16))
        append("\nlat=").append(BigDecimal.valueOf(lat).stripTrailingZeros().toPlainString())
        append("\nlng=").append(BigDecimal.valueOf(lng).stripTrailingZeros().toPlainString())
        append("\nentered_at=").append(recordingInstant(enteredAt))
        append("\nleft_at=").append(recordingInstant(leftAt))
        append("\nstatus=").append(status.name.lowercase())
        append("\nis_interpolated=").append(isInterpolated)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun recordingInstant(epochMillis: Long): String {
    val seconds = epochMillis / 1_000
    val millis = (epochMillis % 1_000).toInt()
    val base = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(seconds * 1_000))
    if (millis == 0) return "${base}Z"
    val fraction = millis.toString().padStart(3, '0').trimEnd('0')
    return "$base.${fraction}Z"
}
