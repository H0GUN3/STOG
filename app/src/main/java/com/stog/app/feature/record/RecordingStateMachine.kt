package com.stog.app.feature.record

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RecordingTuning(
    val locationDistanceFilterMeters: Double,
    val cellTransitionConfirm: Int,
    val dormantAfterMillis: Long,
    val visitDwellMillis: Long,
    val batteryCutoffPercent: Int,
) {
    init {
        require(locationDistanceFilterMeters > 0)
        require(cellTransitionConfirm > 0)
        require(dormantAfterMillis > 0)
        require(visitDwellMillis > 0)
        require(batteryCutoffPercent in 0..100)
    }
}

enum class CollectorState { INACTIVE, STARTING, ACTIVE, DORMANT, BLOCKED, ENDED }
enum class PermissionState { UNKNOWN, GRANTED, DENIED, REVOKED }

data class MemberRecordingState(
    val accountId: String,
    val tripId: String,
    val userId: String,
    val collectorState: CollectorState,
    val permissionState: PermissionState,
    val modeVersion: Long = 0,
    val tripEndAt: Long? = null,
    val batteryLow: Boolean = false,
    val activeCell: Long? = null,
    val activeLat: Double? = null,
    val activeLng: Double? = null,
    val enteredAt: Long? = null,
    val candidateCell: Long? = null,
    val candidateCount: Int = 0,
    val lastAcceptedLat: Double? = null,
    val lastAcceptedLng: Double? = null,
    val lastAcceptedAt: Long? = null,
    val lastTransitionAt: Long? = null,
)

sealed interface RecordingEvent {
    data object CollectorStartRequested : RecordingEvent
    data class CollectorStartCompleted(val successful: Boolean) : RecordingEvent
    data class PermissionChanged(val state: PermissionState) : RecordingEvent
    data class BatteryChanged(val percent: Int) : RecordingEvent
    data class Location(
        val cell: Long,
        val lat: Double,
        val lng: Double,
        val observedAt: Long,
    ) : RecordingEvent
    data class TimeChanged(val now: Long) : RecordingEvent
    data class TripEnded(val at: Long) : RecordingEvent
    data object MotionDetected : RecordingEvent
    data object OsBlocked : RecordingEvent
}

sealed interface RecordingEffect {
    data class PublishCollectionState(val state: CollectorState, val permissionState: PermissionState) : RecordingEffect
    data class EnqueueVisit(
        val cell: Long,
        val lat: Double,
        val lng: Double,
        val enteredAt: Long,
        val leftAt: Long,
        val visited: Boolean,
    ) : RecordingEffect
    data class NotifyActionRequired(val reason: FailureReason) : RecordingEffect
    data object NotifyTripEnded : RecordingEffect
    data object StopCollector : RecordingEffect
}

enum class FailureReason { PERMISSION, BATTERY, OS_BLOCK }

data class RecordingResult(
    val state: MemberRecordingState,
    val effects: List<RecordingEffect> = emptyList(),
)

data class MemberRecordingResult(
    val states: Map<String, MemberRecordingState>,
    val effects: List<RecordingEffect>,
)

fun reduceMemberRecording(
    states: Map<String, MemberRecordingState>,
    userId: String,
    event: RecordingEvent,
    tuning: RecordingTuning,
): MemberRecordingResult {
    val current = requireNotNull(states[userId])
    val result = reduceRecording(current, event, tuning)
    return MemberRecordingResult(states + (userId to result.state), result.effects)
}

fun reduceRecording(
    state: MemberRecordingState,
    event: RecordingEvent,
    tuning: RecordingTuning,
): RecordingResult {
    if (state.collectorState == CollectorState.ENDED && event !is RecordingEvent.TripEnded) {
        return RecordingResult(state)
    }
    return when (event) {
        RecordingEvent.CollectorStartRequested -> if (
            state.collectorState == CollectorState.INACTIVE || state.collectorState == CollectorState.ACTIVE
        ) {
            RecordingResult(state.copy(collectorState = CollectorState.STARTING))
        } else {
            RecordingResult(state)
        }
        is RecordingEvent.CollectorStartCompleted -> if (state.collectorState != CollectorState.STARTING) {
            RecordingResult(state)
        } else if (event.successful) {
            val active = state.copy(collectorState = CollectorState.ACTIVE, permissionState = PermissionState.GRANTED)
            RecordingResult(active, listOf(RecordingEffect.PublishCollectionState(active.collectorState, active.permissionState)))
        } else {
            blocked(state, FailureReason.OS_BLOCK)
        }
        is RecordingEvent.PermissionChanged -> when (event.state) {
            PermissionState.GRANTED -> RecordingResult(state.copy(permissionState = event.state))
            PermissionState.UNKNOWN -> RecordingResult(state.copy(permissionState = event.state))
            PermissionState.DENIED, PermissionState.REVOKED -> blocked(
                state.copy(permissionState = event.state),
                FailureReason.PERMISSION,
            )
        }
        is RecordingEvent.BatteryChanged -> when {
            event.percent <= tuning.batteryCutoffPercent && state.collectorState == CollectorState.ACTIVE -> {
                val dormant = state.copy(collectorState = CollectorState.DORMANT, batteryLow = true)
                RecordingResult(
                    dormant,
                    listOf(
                        RecordingEffect.StopCollector,
                        RecordingEffect.PublishCollectionState(CollectorState.DORMANT, dormant.permissionState),
                        RecordingEffect.NotifyActionRequired(FailureReason.BATTERY),
                    ),
                )
            }
            event.percent <= tuning.batteryCutoffPercent -> RecordingResult(state.copy(batteryLow = true))
            else -> RecordingResult(state.copy(batteryLow = false))
        }
        is RecordingEvent.Location -> onLocation(state, event, tuning)
        is RecordingEvent.TimeChanged -> {
            val transitionAt = state.lastTransitionAt ?: state.enteredAt
            if (state.collectorState == CollectorState.ACTIVE && transitionAt != null &&
                event.now - transitionAt >= tuning.dormantAfterMillis
            ) {
                val dormant = state.copy(collectorState = CollectorState.DORMANT)
                RecordingResult(
                    dormant,
                    listOf(
                        RecordingEffect.StopCollector,
                        RecordingEffect.PublishCollectionState(CollectorState.DORMANT, dormant.permissionState),
                    ),
                )
            } else {
                RecordingResult(state)
            }
        }
        is RecordingEvent.TripEnded -> {
            val ended = state.copy(collectorState = CollectorState.ENDED)
            RecordingResult(
                ended,
                listOf(
                    RecordingEffect.StopCollector,
                    RecordingEffect.PublishCollectionState(CollectorState.ENDED, ended.permissionState),
                    RecordingEffect.NotifyTripEnded,
                ),
            )
        }
        RecordingEvent.MotionDetected -> if (state.collectorState == CollectorState.DORMANT && !state.batteryLow) {
            RecordingResult(state.copy(collectorState = CollectorState.STARTING))
        } else {
            RecordingResult(state)
        }
        RecordingEvent.OsBlocked -> blocked(state, FailureReason.OS_BLOCK)
    }
}

private fun onLocation(
    state: MemberRecordingState,
    event: RecordingEvent.Location,
    tuning: RecordingTuning,
): RecordingResult {
    if (state.collectorState != CollectorState.ACTIVE) return RecordingResult(state)
    if (state.lastAcceptedAt?.let { event.observedAt <= it } == true) return RecordingResult(state)
    val previousLat = state.lastAcceptedLat
    val previousLng = state.lastAcceptedLng
    if (previousLat != null && previousLng != null &&
        distanceMeters(previousLat, previousLng, event.lat, event.lng) < tuning.locationDistanceFilterMeters
    ) {
        return RecordingResult(state)
    }

    val accepted = state.copy(
        lastAcceptedLat = event.lat,
        lastAcceptedLng = event.lng,
        lastAcceptedAt = event.observedAt,
    )
    val stableCell = accepted.activeCell
    if (stableCell == null) {
        return RecordingResult(
            accepted.copy(
                activeCell = event.cell,
                activeLat = event.lat,
                activeLng = event.lng,
                enteredAt = event.observedAt,
                lastTransitionAt = event.observedAt,
            ),
        )
    }
    if (event.cell == stableCell) {
        return RecordingResult(accepted.copy(candidateCell = null, candidateCount = 0))
    }
    val nextCount = if (accepted.candidateCell == event.cell) accepted.candidateCount + 1 else 1
    if (nextCount < tuning.cellTransitionConfirm) {
        return RecordingResult(accepted.copy(candidateCell = event.cell, candidateCount = nextCount))
    }

    val enteredAt = requireNotNull(accepted.enteredAt)
    val effect = RecordingEffect.EnqueueVisit(
        cell = stableCell,
        lat = requireNotNull(accepted.activeLat),
        lng = requireNotNull(accepted.activeLng),
        enteredAt = enteredAt,
        leftAt = event.observedAt,
        visited = event.observedAt - enteredAt >= tuning.visitDwellMillis,
    )
    return RecordingResult(
        accepted.copy(
            activeCell = event.cell,
            activeLat = event.lat,
            activeLng = event.lng,
            enteredAt = event.observedAt,
            candidateCell = null,
            candidateCount = 0,
            lastTransitionAt = event.observedAt,
        ),
        listOf(effect),
    )
}

private fun blocked(state: MemberRecordingState, reason: FailureReason): RecordingResult {
    val blocked = state.copy(collectorState = CollectorState.BLOCKED)
    return RecordingResult(
        blocked,
        listOf(
            RecordingEffect.StopCollector,
            RecordingEffect.PublishCollectionState(CollectorState.BLOCKED, blocked.permissionState),
            RecordingEffect.NotifyActionRequired(reason),
        ),
    )
}

private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val radiusMeters = 6_371_000.0
    val latDelta = Math.toRadians(lat2 - lat1)
    val lngDelta = Math.toRadians(lng2 - lng1)
    val a = sin(latDelta / 2) * sin(latDelta / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(lngDelta / 2) * sin(lngDelta / 2)
    return radiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
}

data class RecordingDate(val year: Int, val month: Int, val day: Int) : Comparable<RecordingDate> {
    override fun compareTo(other: RecordingDate): Int = compareValuesBy(this, other, RecordingDate::year, RecordingDate::month, RecordingDate::day)

    companion object {
        fun parse(value: String): RecordingDate {
            val parts = value.split('-')
            require(parts.size == 3) { "Recording date must use ISO calendar form" }
            return RecordingDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

data class RecordingTrip(
    val tripId: String,
    val plannedStartDate: RecordingDate?,
    val plannedEndDate: RecordingDate?,
    val ended: Boolean,
)

sealed interface ActivationTrigger {
    data class Calendar(val date: RecordingDate) : ActivationTrigger
    data class Destination(val tripId: String) : ActivationTrigger
}

fun tripsForActivation(trips: List<RecordingTrip>, trigger: ActivationTrigger): List<RecordingTrip> = when (trigger) {
    is ActivationTrigger.Calendar -> trips.filter { trip ->
        !trip.ended && trip.plannedStartDate != null && trip.plannedEndDate != null &&
            trigger.date >= trip.plannedStartDate && trigger.date <= trip.plannedEndDate
    }
    is ActivationTrigger.Destination -> trips.filter { !it.ended && it.tripId == trigger.tripId }
}
