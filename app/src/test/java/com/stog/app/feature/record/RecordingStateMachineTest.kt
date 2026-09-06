package com.stog.app.feature.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    private val tuning = RecordingTuning(
        locationDistanceFilterMeters = 50.0,
        cellTransitionConfirm = 2,
        dormantAfterMillis = 60 * 60 * 1_000L,
        visitDwellMillis = 30 * 60 * 1_000L,
        batteryCutoffPercent = 15,
    )

    @Test
    fun filtersCallbacksAndRequiresTwoConsecutiveCellObservations() {
        var state = member()
        val first = reduceRecording(state, RecordingEvent.Location(cell = 10, lat = 35.0, lng = 127.0, observedAt = 0), tuning)
        state = first.state
        assertTrue(first.effects.isEmpty())

        val filtered = reduceRecording(state, RecordingEvent.Location(cell = 11, lat = 35.0001, lng = 127.0, observedAt = 1_000), tuning)
        assertEquals(state, filtered.state)

        state = reduceRecording(state, RecordingEvent.Location(cell = 11, lat = 35.001, lng = 127.0, observedAt = 2_000), tuning).state
        assertEquals(10L, state.activeCell)
        assertEquals(1, state.candidateCount)

        val confirmed = reduceRecording(state, RecordingEvent.Location(cell = 11, lat = 35.002, lng = 127.0, observedAt = 3_000), tuning)
        assertEquals(11L, confirmed.state.activeCell)
        assertEquals(1, confirmed.effects.filterIsInstance<RecordingEffect.EnqueueVisit>().size)
    }

    @Test
    fun membersRemainIndependent() {
        val members = mapOf("member-a" to member("member-a"), "member-b" to member("member-b"))
        val updated = reduceMemberRecording(
            members,
            "member-a",
            RecordingEvent.BatteryChanged(tuning.batteryCutoffPercent),
            tuning,
        )

        assertEquals(CollectorState.DORMANT, updated.states.getValue("member-a").collectorState)
        assertEquals(CollectorState.ACTIVE, updated.states.getValue("member-b").collectorState)
    }

    @Test
    fun batteryCutoffDormancyAndEndedAreDistinct() {
        val dormant = reduceRecording(
            member(),
            RecordingEvent.BatteryChanged(tuning.batteryCutoffPercent),
            tuning,
        )
        assertEquals(CollectorState.DORMANT, dormant.state.collectorState)
        assertTrue(dormant.effects.any { it is RecordingEffect.NotifyActionRequired })

        val ended = reduceRecording(dormant.state, RecordingEvent.TripEnded(2_000), tuning)
        assertEquals(CollectorState.ENDED, ended.state.collectorState)
        assertTrue(ended.effects.any { it == RecordingEffect.StopCollector })

        val terminal = reduceRecording(ended.state, RecordingEvent.MotionDetected, tuning)
        assertEquals(CollectorState.ENDED, terminal.state.collectorState)
    }

    @Test
    fun tripActivationIsPublishedOnlyAfterCollectorSuccess() {
        val starting = reduceRecording(
            member().copy(collectorState = CollectorState.INACTIVE),
            RecordingEvent.CollectorStartRequested,
            tuning,
        )
        assertEquals(CollectorState.STARTING, starting.state.collectorState)
        assertFalse(starting.effects.any { it is RecordingEffect.PublishCollectionState })

        val blocked = reduceRecording(starting.state, RecordingEvent.CollectorStartCompleted(false), tuning)
        assertEquals(CollectorState.BLOCKED, blocked.state.collectorState)
        assertFalse(blocked.effects.any { it is RecordingEffect.PublishCollectionState && it.state == CollectorState.ACTIVE })

        val active = reduceRecording(starting.state, RecordingEvent.CollectorStartCompleted(true), tuning)
        assertEquals(CollectorState.ACTIVE, active.state.collectorState)
        assertTrue(active.effects.any { it is RecordingEffect.PublishCollectionState && it.state == CollectorState.ACTIVE })
    }

    @Test
    fun collectorSuccessIsIgnoredOutsideStartingAndLowBatteryLatchesDormantResume() {
        val blocked = member().copy(collectorState = CollectorState.BLOCKED)
        assertEquals(
            CollectorState.BLOCKED,
            reduceRecording(blocked, RecordingEvent.CollectorStartCompleted(true), tuning).state.collectorState,
        )

        val low = reduceRecording(member(), RecordingEvent.BatteryChanged(tuning.batteryCutoffPercent), tuning).state
        assertTrue(low.batteryLow)
        assertEquals(CollectorState.DORMANT, low.collectorState)
        assertEquals(
            CollectorState.DORMANT,
            reduceRecording(low, RecordingEvent.MotionDetected, tuning).state.collectorState,
        )
        val charged = reduceRecording(low, RecordingEvent.BatteryChanged(tuning.batteryCutoffPercent + 1), tuning).state
        assertFalse(charged.batteryLow)
        assertEquals(
            CollectorState.STARTING,
            reduceRecording(charged, RecordingEvent.MotionDetected, tuning).state.collectorState,
        )
    }

    @Test
    fun duplicateAndOutOfOrderCallbacksCannotAdvanceCellCandidate() {
        val seeded = reduceRecording(
            member(),
            RecordingEvent.Location(10, 35.0, 127.0, 2_000),
            tuning,
        ).state
        val firstCandidate = reduceRecording(
            seeded,
            RecordingEvent.Location(11, 35.001, 127.0, 3_000),
            tuning,
        ).state
        assertEquals(firstCandidate, reduceRecording(
            firstCandidate,
            RecordingEvent.Location(11, 35.002, 127.0, 3_000),
            tuning,
        ).state)
        assertEquals(firstCandidate, reduceRecording(
            firstCandidate,
            RecordingEvent.Location(11, 35.002, 127.0, 2_500),
            tuning,
        ).state)
    }

    @Test
    fun permissionRevocationAndOsBlockFailClosed() {
        val revoked = reduceRecording(member(), RecordingEvent.PermissionChanged(PermissionState.REVOKED), tuning)
        assertEquals(CollectorState.BLOCKED, revoked.state.collectorState)
        assertTrue(revoked.effects.contains(RecordingEffect.StopCollector))
        assertTrue(revoked.effects.any { it is RecordingEffect.NotifyActionRequired })

        val blocked = reduceRecording(member(), RecordingEvent.OsBlocked, tuning)
        assertEquals(CollectorState.BLOCKED, blocked.state.collectorState)
        assertTrue(blocked.effects.any { it is RecordingEffect.NotifyActionRequired })
    }

    @Test
    fun calendarAndDestinationActivationAreDeterministic() {
        val today = RecordingDate(2026, 8, 24)
        val trips = listOf(
            RecordingTrip("1", RecordingDate(2026, 8, 24), RecordingDate(2026, 8, 25), ended = false),
            RecordingTrip("2", RecordingDate(2026, 8, 25), RecordingDate(2026, 8, 26), ended = false),
            RecordingTrip("3", RecordingDate(2026, 8, 23), RecordingDate(2026, 8, 24), ended = true),
        )
        assertEquals(listOf("1"), tripsForActivation(trips, ActivationTrigger.Calendar(today)).map { it.tripId })
        assertEquals(listOf("2"), tripsForActivation(trips, ActivationTrigger.Destination("2")).map { it.tripId })
    }

    private fun member(userId: String = "member-a") = MemberRecordingState(
        accountId = userId,
        tripId = "trip-a",
        userId = userId,
        collectorState = CollectorState.ACTIVE,
        permissionState = PermissionState.GRANTED,
    )
}
