package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventApiClientTest {
    @Test
    fun parsesTourApiEventsAndDropsNonCanonicalCandidates() {
        val events = parseNearbyEvents(
            listOf(
                EventSearchCandidateResponse(
                    provider = "tour_api",
                    externalId = "1001",
                    title = "전주 비빔밥 축제",
                    venueName = "전주월드컵경기장",
                    address = "전북 전주시",
                    latitude = 35.846,
                    longitude = 127.126,
                    startsOn = "2026-10-10",
                    endsOn = "2026-10-12",
                    detailUri = null,
                    imageUri = "https://example.test/festival.jpg",
                    provenanceKind = "canonical",
                    provenanceSource = "tour_api",
                ),
                EventSearchCandidateResponse(
                    provider = "google",
                    externalId = "1002",
                    title = "provider result",
                    venueName = null,
                    address = null,
                    latitude = 35.846,
                    longitude = 127.126,
                    startsOn = "2026-10-10",
                    endsOn = "2026-10-12",
                    detailUri = null,
                    imageUri = null,
                    provenanceKind = "provider",
                    provenanceSource = "google",
                ),
            ),
        )

        assertEquals(1, events.size)
        assertEquals("전주 비빔밥 축제", events.single().title)
        assertEquals(EventSearchProvenance.TourApi, events.single().provenance)
    }

    @Test
    fun emptyEventArrayProducesEmptyState() {
        assertTrue(parseNearbyEvents(emptyList()).isEmpty())
    }
}
