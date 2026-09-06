package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchPanelRequestGateTest {
    @Test
    fun staleSearchCompletionCannotReplaceResultsForANewlySelectedTrip() {
        val gate = TripRequestGate()
        val firstTripRequest = gate.capture()
        gate.invalidate()
        var candidates = listOf("new trip result")
        var message = "new trip message"
        var searching = true

        gate.applyIfCurrent(firstTripRequest) {
            candidates = listOf("first trip result")
            message = "first trip message"
            searching = false
        }

        assertEquals(listOf("new trip result"), candidates)
        assertEquals("new trip message", message)
        assertTrue(searching)
    }

    @Test
    fun currentRequestCompletionStillUpdatesTheSelectedTrip() {
        val gate = TripRequestGate()
        val request = gate.capture()
        var candidates = emptyList<String>()

        gate.applyIfCurrent(request) {
            candidates = listOf("selected trip result")
        }

        assertEquals(listOf("selected trip result"), candidates)
    }

    @Test
    fun beginningANewCellRequestInvalidatesThePreviousSelection() {
        val gate = TripRequestGate()
        val staleRequest = gate.capture()
        val currentRequest = gate.begin()
        var applied = false

        gate.applyIfCurrent(staleRequest) { applied = true }
        gate.applyIfCurrent(currentRequest) { applied = true }

        assertTrue(applied)
        assertEquals(currentRequest, gate.capture())
    }

    @Test
    fun staleSaveCompletionCannotMarkACandidateSavedForANewlySelectedTrip() {
        val gate = TripRequestGate()
        val firstTripRequest = gate.capture()
        gate.invalidate()
        var savedExternalIds = setOf("new-trip-place")
        var message = "new trip message"
        var savingExternalId: String? = "new-trip-place"

        gate.applyIfCurrent(firstTripRequest) {
            savedExternalIds += "first-trip-place"
            message = "first trip message"
            savingExternalId = null
        }

        assertEquals(setOf("new-trip-place"), savedExternalIds)
        assertEquals("new trip message", message)
        assertEquals("new-trip-place", savingExternalId)
    }
}
