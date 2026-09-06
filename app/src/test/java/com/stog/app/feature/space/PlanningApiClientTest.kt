package com.stog.app.feature.space

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlanningApiClientTest {
    @Test
    fun tripPayloadUsesCanonicalFieldNames() {
        val payload = tripRequestPayload("전주 여행", "tour")

        assertEquals("전주 여행", payload.title)
        assertEquals("tour", payload.activityType)
    }

    @Test
    fun tripPayloadKeepsPlanningDateRange() {
        val payload = tripRequestPayload(
            title = "전주 여행",
            activityType = "tour",
            plannedStartDate = "2026-09-01",
            plannedEndDate = "2026-09-03",
        )

        assertEquals("2026-09-01", payload.plannedStartDate)
        assertEquals("2026-09-03", payload.plannedEndDate)
    }

    @Test
    fun tripPayloadSendsSelectedJeonbukRegion() {
        val payload = tripRequestPayload(
            title = "부안 여행",
            activityType = "tour",
            plannedStartDate = "2026-09-01",
            plannedEndDate = "2026-09-03",
            regionCode = "BUAN",
        )

        assertEquals("BUAN", payload.toJson().getString("region_code"))
    }

    @Test
    fun basketPayloadKeepsGoogleCandidateAndCoordinates() {
        val payload = basketRequestPayload(
            tripId = 7L,
            candidate = PlaceSearchCandidate(
                externalId = "ChIJexample",
                name = "전주 카페",
                address = "전주시",
                latitude = 35.815,
                longitude = 127.15,
                types = listOf("cafe"),
            ),
        )

        assertEquals(7L, payload.tripId)
        assertEquals("google", payload.provider)
        assertEquals("ChIJexample", payload.externalId)
        assertEquals("cafe", payload.category)
        assertEquals(35.815, payload.latitude!!, 0.000001)
        assertEquals(127.15, payload.longitude!!, 0.000001)
    }

    @Test
    fun basketPayloadUsesCanonicalProviderForCatalogCandidate() {
        val payload = basketRequestPayload(
            tripId = 9L,
            candidate = PlaceSearchCandidate(
                externalId = "tour-api-123",
                name = "전주 한옥마을",
                address = "전주시",
                latitude = 35.815,
                longitude = 127.15,
                types = listOf("tourist_attraction"),
                provenance = PlaceSearchProvenance.Canonical(
                    placeId = 11L,
                    sourceType = "public_data",
                    sourceId = 21L,
                    catalogStatus = "public",
                ),
            ),
        )

        assertEquals("canonical", payload.provider)
        assertEquals("tour-api-123", payload.externalId)
        assertEquals("attraction", payload.category)
        assertEquals(11L, payload.canonicalPlaceId)
        assertEquals(21L, payload.canonicalSourceId)
    }

    @Test
    fun confirmedBasketPayloadKeepsImmutableClientIdentityAndCanonicalFingerprint() {
        val payload = basketRequestPayload(
            tripId = 11L,
            clientItemId = "c0a80101-0000-4000-8000-000000000009",
            candidate = PlaceSearchCandidate(
                externalId = "google-1",
                name = "첫째",
                address = "주소",
                latitude = 35.8,
                longitude = 127.1,
                types = listOf("cafe"),
            ),
        )

        assertEquals("c0a80101-0000-4000-8000-000000000009", payload.clientItemId)
        assertEquals("cbf94172c51dd02097df2e23fe459abaa89c788902c85ab61e4097ef24182d2c", payload.payloadFingerprint)
    }

    @Test
    fun tripSummaryKeepsSelectionFields() {
        val trip = tripSummary(8L, "전주 여행", "tour", "dormant", "private")

        assertEquals(8L, trip.id)
        assertEquals("전주 여행", trip.title)
        assertEquals("tour", trip.activityType)
        assertEquals("dormant", trip.mode)
        assertEquals("private", trip.visibility)
    }

    @Test
    fun homeSummaryKeepsServerMetricsAndTrips() {
        val home = PlanningApiClient("").parseHomeSummary(
            """{"monthly_trip_count":2,"visited_cell_count":8,"saved_place_count":14,"monthly_received_like_count":56,"trips":[
              {"id":8,"title":"전주 여행","activity_type":"tour","mode":"dormant","visibility":"private","planned_start_date":"2026-08-22","planned_end_date":"2026-08-24"}
            ]}""",
        )

        assertEquals(2L, home.monthlyTripCount)
        assertEquals(8L, home.visitedCellCount)
        assertEquals(14L, home.savedPlaceCount)
        assertEquals(56L, home.monthlyReceivedLikeCount)
        assertEquals(listOf(8L), home.trips.map(TripSummary::id))
    }

    @Test
    fun homeSummaryMapsJsonNullPlanningDatesToNull() {
        val home = PlanningApiClient("").parseHomeSummary(
            """{"monthly_trip_count":0,"visited_cell_count":0,"saved_place_count":0,"monthly_received_like_count":0,"trips":[
              {"id":8,"title":"날짜 미정 여행","activity_type":"tour","mode":"dormant","visibility":"private","planned_start_date":null,"planned_end_date":null}
            ]}""",
        )

        assertEquals(null, home.trips.single().plannedStartDate)
        assertEquals(null, home.trips.single().plannedEndDate)
    }

    @Test
    fun basketItemsKeepPlaceImageUrl() {
        val items = parseBasketItems(
            """[{"id":11,"item_type":"place","title":"전주한옥마을",
                "category":"tourist_attraction","source":"canonical",
                "status":"resolved","added_at":"2026-08-25T00:00:00Z",
                "image_url":"https://example.com/hanok.jpg"}]""",
        )

        assertEquals("https://example.com/hanok.jpg", items.single().imageUrl)
    }

    @Test
    fun basketItemsTreatJsonNullImageUrlAsMissing() {
        val items = parseBasketItems(
            """[{"id":11,"item_type":"place","title":"전주한옥마을",
                "category":"tourist_attraction","source":"canonical",
                "status":"resolved","added_at":"2026-08-25T00:00:00Z",
                "image_url":null}]""",
        )

        assertNull(items.single().imageUrl)
    }

    @Test
    fun itineraryItemsKeepOnlyConfirmedPlaceDetails() {
        val items = itineraryItemsFromResponses(
            listOf(
                ItineraryItemResponse(
                    basketItemId = 11L,
                    dayNumber = 1,
                    orderIndex = 0,
                    plannedArrival = "10:00",
                    plannedDurationMin = 90,
                    placeId = 21L,
                    title = "전주한옥마을",
                    category = "tourist_attraction",
                    latitude = 35.815,
                    longitude = 127.153,
                    address = "전북 전주시 완산구",
                    imageUrl = "https://example.com/hanok.jpg",
                ),
            ),
        )

        assertEquals(21L, items.single().placeId)
        assertEquals(35.815, items.single().latitude)
        assertEquals("전북 전주시 완산구", items.single().address)
        assertEquals("https://example.com/hanok.jpg", items.single().imageUrl)
    }

    @Test
    fun itineraryItemsTreatJsonNullImageUrlAsMissing() {
        val items = parseItineraryItems(
            """{"items":[{"basket_item_id":11,"day_number":1,"order_index":0,
                "planned_arrival":"10:00","planned_duration_min":90,
                "is_fixed":false,"image_url":null}]}""",
        )

        assertNull(items.single().imageUrl)
    }

    @Test
    fun membershipResponsesKeepViewerAuthorityAndTypedParticipants() {
        val members = parseTripMembers(
            """{"viewer_id":4,"owner_id":3,"members":[
              {"user_id":3,"nickname":"owner","joined_at":"2026-08-25T00:00:00Z"},
              {"user_id":4,"nickname":"member","joined_at":"2026-08-25T01:00:00Z"}
            ]}""",
        )
        val invite = parseTripInvite("""{"token":"invite-code","join_path":"/trip-invites/invite-code/join"}""")
        val joined = parseJoinedTrip("""{"trip_id":9}""")

        assertEquals(4L, members.viewerId)
        assertEquals(3L, members.ownerId)
        assertEquals(listOf("owner", "member"), members.members.map(TripMember::nickname))
        assertEquals("invite-code", invite.token)
        assertEquals(9L, joined.tripId)
    }

    @Test
    fun lifecycleAndAppendOnlyHistoryResponsesKeepServerIdentityAndOrder() {
        val changes = parseItineraryChanges(
            """{"changes":[
              {"id":7,"user_id":3,"action":"replace","payload":"{}","created_at":"2026-08-25T01:00:00Z"},
              {"id":8,"user_id":4,"action":"replace","payload":"{}","created_at":"2026-08-25T01:01:00Z"}
            ]}""",
        )
        val mode = parseTripModeUpdate(
            """{"trip_id":9,"mode":"ended","started_at":"2026-08-25T00:00:00Z","ended_at":"2026-08-25T02:00:00Z"}""",
        )

        assertEquals(listOf(7L, 8L), changes.map(ItineraryChange::id))
        assertEquals(listOf(3L, 4L), changes.map(ItineraryChange::userId))
        assertEquals(9L, mode.tripId)
        assertEquals("ended", mode.mode)
        assertEquals("2026-08-25T02:00:00Z", mode.endedAt)

        val archive = parseTripArchive(
            """{"trip_id":9,"mode":"ended","itinerary_item_count":4,"itinerary_change_count":2,"visit_count":3,"visited_count":2,"passed_count":1,"visited_cell_count":2,"photo_count":5,"trail":[{"visit_id":11,"user_id":3,"cell_id":"8a2a1072b59ffff","latitude":35.815,"longitude":127.15,"entered_at":"2026-08-25T00:00:00Z","left_at":"2026-08-25T00:30:00Z","status":"visited","is_interpolated":false}]}""",
        )
        assertEquals(2L, archive.visitedCount)
        assertEquals(1L, archive.passedCount)
        assertEquals(5L, archive.photoCount)
        assertEquals(listOf(11L), archive.trail.map(TripArchiveTrailPoint::visitId))
        assertEquals("8a2a1072b59ffff", archive.trail.single().cellId)
        assertEquals(false, archive.trail.single().isInterpolated)
    }
}
