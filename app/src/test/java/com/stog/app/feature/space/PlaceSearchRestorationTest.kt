package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaceSearchRestorationTest {
    @Test
    fun restoredSearchCandidatesRecreateCanonicalAndProviderMarkers() {
        val candidates = listOf(
            PlaceSearchCandidate(
                externalId = "tour-123",
                name = "전주 한옥마을",
                address = "전북 전주시",
                latitude = 35.815,
                longitude = 127.153,
                types = listOf("tourist_attraction"),
                provenance = PlaceSearchProvenance.Canonical(
                    placeId = 11,
                    sourceType = "tour_api",
                    sourceId = 21,
                    catalogStatus = "public",
                ),
            ),
            PlaceSearchCandidate(
                externalId = "ChIJfallback",
                name = "전주 카페",
                address = "전북 전주시",
                latitude = 35.816,
                longitude = 127.154,
                types = listOf("cafe"),
                rating = 4.4,
                userRatingCount = 12,
            ),
        )

        val restored = decodePlaceSearchCandidates(encodePlaceSearchCandidates(candidates))

        assertEquals(candidates, restored)
        assertEquals(placeMarkersFor(candidates), placeMarkersFor(restored))
    }

    @Test
    fun malformedRestoredCandidateIsIgnoredWithoutDroppingValidMarkers() {
        val valid = PlaceSearchCandidate(
            externalId = "ChIJvalid",
            name = "유효 장소",
            address = null,
            latitude = 35.816,
            longitude = 127.154,
        )

        val restored = decodePlaceSearchCandidates(
            listOf("not-json") + encodePlaceSearchCandidates(listOf(valid)),
        )

        assertEquals(listOf(valid), restored)
        assertTrue(placeMarkersFor(restored).isNotEmpty())
    }
}
