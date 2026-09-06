package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchParserTest {
    @Test
    fun preservesCanonicalPhotoUrlsThroughCandidateAndDetailsConversion() {
        val photoUrls = listOf("https://images.example.test/hanok.jpg")
        val candidate = parsePlaceSearchCandidates(
            listOf(
                PlaceSearchCandidateResponse(
                    provider = "canonical",
                    externalId = "tour-photo",
                    name = "전주 한옥마을",
                    address = "전북 전주시 완산구",
                    latitude = 35.815,
                    longitude = 127.153,
                    photoUrls = photoUrls,
                    provenance = PlaceSearchProvenanceResponse(
                        kind = "canonical",
                        placeId = 11L,
                        sourceType = "TOUR_API",
                        sourceId = 22L,
                        catalogStatus = "public",
                    ),
                ),
            ),
        ).single()

        assertEquals(photoUrls, candidate.photoUrls)
        assertEquals(photoUrls, candidate.toPlaceDetails().photoUrls)
    }

    @Test
    fun parsesCanonicalAndGoogleFallbackCandidatesInBackendRankOrder() {
        val candidates = parsePlaceSearchCandidates(
            listOf(
                PlaceSearchCandidateResponse(
                    provider = "canonical",
                    externalId = "tour-11",
                    name = "전주 한옥마을",
                    address = "전북 전주시 완산구",
                    latitude = 35.815,
                    longitude = 127.153,
                    types = listOf("tourist_attraction"),
                    regularOpeningHours = listOf("매일 09:00-18:00"),
                    provenance = PlaceSearchProvenanceResponse(
                        kind = "canonical",
                        placeId = 11,
                        sourceType = "public_data",
                        sourceId = 4,
                        catalogStatus = "public",
                    ),
                ),
                PlaceSearchCandidateResponse(
                    provider = "google",
                    externalId = "ChIJfallback",
                    name = "Google fallback",
                    address = null,
                    latitude = 35.816,
                    longitude = 127.154,
                    types = listOf("cafe"),
                    rating = 4.5,
                    userRatingCount = 89,
                    businessStatus = "OPERATIONAL",
                    openNow = true,
                    nextCloseTime = "2026-08-24T22:00:00+09:00",
                    provenance = PlaceSearchProvenanceResponse(
                        kind = "provider",
                        placeId = null,
                        sourceType = "google",
                        sourceId = null,
                        catalogStatus = null,
                    ),
                ),
            ),
        )

        assertEquals(listOf("tour-11", "ChIJfallback"), candidates.map { it.externalId })
        assertEquals(
            PlaceSearchProvenance.Canonical(11, "public_data", 4, "public"),
            candidates.first().provenance,
        )
        assertEquals(PlaceSearchProvenance.GoogleFallback, candidates.last().provenance)
        assertEquals(4.5, candidates.last().rating!!, 0.001)
        assertEquals(89, candidates.last().userRatingCount)
        assertEquals(true, candidates.last().openNow)

        val details = candidates.first().toPlaceDetails()
        assertEquals("전주 한옥마을", details.name)
        assertEquals(listOf("매일 09:00-18:00"), details.regularOpeningHours)
        assertEquals(candidates.first().provenance, details.provenance)
    }

    @Test
    fun skipsMalformedCandidatesWithoutReorderingValidBackendCandidates() {
        val candidates = parsePlaceSearchCandidates(
            listOf(
                canonicalResponse(externalId = "first", name = "첫 번째", placeId = 1),
                PlaceSearchCandidateResponse(
                    provider = "canonical",
                    externalId = "broken",
                    name = "잘못된 후보",
                    address = null,
                    latitude = null,
                    longitude = null,
                    provenance = PlaceSearchProvenanceResponse(
                        kind = "canonical",
                        placeId = 2,
                        sourceType = null,
                        sourceId = null,
                        catalogStatus = null,
                    ),
                ),
                PlaceSearchCandidateResponse(
                    provider = "google",
                    externalId = "last",
                    name = "마지막",
                    address = null,
                    latitude = null,
                    longitude = null,
                    provenance = PlaceSearchProvenanceResponse(
                        kind = "provider",
                        placeId = null,
                        sourceType = "google",
                        sourceId = null,
                        catalogStatus = null,
                    ),
                ),
            ),
        )

        assertEquals(listOf("first", "last"), candidates.map { it.externalId })
    }

    @Test
    fun emptyCandidateArrayIsAnEmptySearchResult() {
        assertTrue(parsePlaceSearchCandidates(emptyList()).isEmpty())
    }

    private fun canonicalResponse(
        externalId: String,
        name: String,
        placeId: Long,
    ): PlaceSearchCandidateResponse = PlaceSearchCandidateResponse(
        provider = "canonical",
        externalId = externalId,
        name = name,
        address = null,
        latitude = null,
        longitude = null,
        provenance = PlaceSearchProvenanceResponse(
            kind = "canonical",
            placeId = placeId,
            sourceType = "public_data",
            sourceId = 10,
            catalogStatus = "public",
        ),
    )
}
