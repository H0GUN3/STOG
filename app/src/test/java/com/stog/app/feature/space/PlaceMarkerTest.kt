package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceMarkerTest {
    @Test
    fun keepsOnlyCandidatesWithValidCoordinates() {
        val markers = placeMarkersFor(
            listOf(
                PlaceSearchCandidate("valid", "유효 장소", null, 35.82, 127.14),
                PlaceSearchCandidate("missing", "좌표 없음", null, null, 127.14),
                PlaceSearchCandidate("invalid", "범위 오류", null, 91.0, 127.14),
            ),
        )

        assertEquals(
            listOf(PlaceMapMarker("valid", "유효 장소", 35.82, 127.14)),
            markers,
        )
    }

    @Test
    fun preservesCanonicalProvenanceWhenResultsReturnAsMapMarkers() {
        val provenance = PlaceSearchProvenance.Canonical(
            placeId = 7,
            sourceType = "public_data",
            sourceId = 3,
            catalogStatus = "public",
        )
        val marker = placeMarkersFor(
            listOf(
                PlaceSearchCandidate(
                    externalId = "catalog-place",
                    name = "카탈로그 장소",
                    address = null,
                    latitude = 35.82,
                    longitude = 127.14,
                    provenance = provenance,
                ),
            ),
        ).single()

        assertEquals(provenance, marker.provenance)
    }
}
