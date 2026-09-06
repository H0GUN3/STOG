package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(PlanningEndpointsTest.TestGooglePlacesConfiguration.class)
class PlanningEndpointsTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GooglePlacesClient googlePlaces;

    @Autowired
    private PlanningService planning;

    @Autowired
    private TripMembershipService memberships;

    @Test
    void guestsCanSearchAndReadPlaces() throws Exception {
        PlaceCandidate candidate = candidate("ChIJpublic", "공개 장소");
        when(googlePlaces.textSearch(any())).thenReturn(List.of(candidate));
        when(googlePlaces.details("ChIJpublic")).thenReturn(candidate);
        when(googlePlaces.photo("places/ChIJpublic/photos/photo")).thenReturn(
            new GooglePlacesClient.PhotoResponse(
                new byte[] {1, 2, 3},
                MediaType.IMAGE_JPEG
            )
        );

        mockMvc.perform(post("/places/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "전주 한옥마을"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates[0].external_id").value("ChIJpublic"));

        mockMvc.perform(get("/places/ChIJpublic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.external_id").value("ChIJpublic"));
        mockMvc.perform(get("/places/photo")
                .param("name", "places/ChIJpublic/photos/photo"))
            .andExpect(status().isOk());
    }

    @Test
    void onlyPlaceSearchAndReadEndpointsArePublic() throws Exception {
        mockMvc.perform(post("/places/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "center": {"latitude": 35.815, "longitude": 127.15},
                      "radius_meters": 1000,
                      "included_types": ["cafe"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidates").isArray());
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "여행", "activity_type": "tour"}
                    """))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/basket-items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trip_id": 1,
                      "provider": "google",
                      "external_id": "ChIJplace",
                      "name": "장소",
                      "category": "cafe",
                      "latitude": 35.815,
                      "longitude": 127.15
                    }
                    """))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/basket-items/link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trip_id": 1,
                      "source": "naver",
                      "original_url": "https://map.naver.com/example",
                      "title": "공유 장소"
                    }
                    """))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/trips/1/basket"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/trips/1"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/trips/1/basket/1"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/trips/1/itinerary"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/trips/1/itinerary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + "\"items\":[]}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedTripCreateAndListRequireACompleteOrderedDateRange() throws Exception {
        long ownerId = user("dated-owner");

        mockMvc.perform(post("/trips")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "날짜 여행",
                      "activity_type": "tour",
                      "planned_start_date": "2026-09-01",
                      "planned_end_date": "2026-09-03"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planned_start_date").value("2026-09-01"))
            .andExpect(jsonPath("$.planned_end_date").value("2026-09-03"));

        mockMvc.perform(get("/trips/me")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("날짜 여행"))
            .andExpect(jsonPath("$[0].mode").value("dormant"))
            .andExpect(jsonPath("$[0].planned_start_date").value("2026-09-01"))
            .andExpect(jsonPath("$[0].planned_end_date").value("2026-09-03"));
        long tripId = jdbc.sql("SELECT id FROM trips WHERE owner_id = :ownerId AND title = '날짜 여행'")
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        mockMvc.perform(get("/trips/{tripId}/collection-state", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.collector_state").value("inactive"))
            .andExpect(jsonPath("$.mode_version").value(0));
        mockMvc.perform(patch("/trips/{tripId}/collection-state", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "collector_state": "active",
                      "permission_state": "granted",
                      "sync_cursor": null,
                      "expected_mode_version": 0
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.collector_state").value("active"));
        mockMvc.perform(get("/trips/me")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].mode").value("active"));

        mockMvc.perform(post("/trips")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "종료일 없는 여행",
                      "activity_type": "tour",
                      "planned_start_date": "2026-09-01"
                    }
                    """))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/trips")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "역순 여행",
                      "activity_type": "tour",
                      "planned_start_date": "2026-09-03",
                      "planned_end_date": "2026-09-01"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void tripDetailReadBackIncludesOwnerUpdates() throws Exception {
        long ownerId = user("trip-detail-owner");
        TripResponses.Created created = planning.createTrip(
            ownerId,
            new TripRequests.Create(
                "수정 전 여행",
                "tour",
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 9, 3)
            )
        );

        mockMvc.perform(get("/trips/{tripId}", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("수정 전 여행"))
            .andExpect(jsonPath("$.planned_start_date").value("2026-09-01"))
            .andExpect(jsonPath("$.planned_end_date").value("2026-09-03"));

        mockMvc.perform(put("/trips/{tripId}", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "수정 후 여행",
                      "planned_start_date": "2026-09-10",
                      "planned_end_date": "2026-09-12"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("수정 후 여행"));

        mockMvc.perform(get("/trips/{tripId}", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("수정 후 여행"))
            .andExpect(jsonPath("$.planned_start_date").value("2026-09-10"))
            .andExpect(jsonPath("$.planned_end_date").value("2026-09-12"));
    }

    @Test
    void basketRemovalIsPersistedAndRemovesItsItineraryRow() throws Exception {
        long ownerId = user("basket-delete-owner");
        TripResponses.Created created = planning.createTrip(
            ownerId,
            new TripRequests.Create(
                "바구니 삭제 여행",
                "tour",
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 9, 3)
            )
        );
        long basketItemId = planning.addLink(
            ownerId,
            new BasketRequests.AddLink(
                created.id(),
                "share",
                "https://example.test/place",
                "삭제할 장소",
                "cafe"
            )
        ).id();

        mockMvc.perform(put("/trips/{tripId}/itinerary", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "items": [{
                        "basket_item_id": %d,
                        "day_number": 1,
                        "order_index": 0,
                        "planned_arrival": "09:00",
                        "planned_duration_min": 30
                      }]
                    }
                    """.formatted(basketItemId)))
            .andExpect(status().isOk());

        mockMvc.perform(delete(
                "/trips/{tripId}/basket/{basketItemId}",
                created.id(),
                basketItemId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/trips/{tripId}/basket", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/trips/{tripId}/itinerary", created.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void authenticatedHomeReturnsServerAggregatesAndTrips() throws Exception {
        long ownerId = user("home-owner");
        java.time.LocalDate start = java.time.LocalDate.now().withDayOfMonth(1);
        planning.createTrip(
            ownerId,
            new TripRequests.Create(
                "홈 집계 여행",
                "tour",
                start,
                start.plusDays(2)
            )
        );

        mockMvc.perform(get("/trips/me/home")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.monthly_trip_count").value(1))
            .andExpect(jsonPath("$.visited_cell_count").value(0))
            .andExpect(jsonPath("$.saved_place_count").value(0))
            .andExpect(jsonPath("$.monthly_received_like_count").value(0))
            .andExpect(jsonPath("$.trips[0].title").value("홈 집계 여행"));
    }

    @Test
    void homeVisitedCellCountIncludesOnlyRealVisitedCells() throws Exception {
        long ownerId = user("home-cell-owner");
        long tripId = trip(ownerId, "home cells");
        long visitedCell = CellIdCalculator.fromCoords(35.815, 127.15);
        long passedCell = CellIdCalculator.fromCoords(35.816, 127.151);
        long interpolatedCell = CellIdCalculator.fromCoords(35.817, 127.152);

        insertVisit(ownerId, tripId, visitedCell, "visited", "home-visited");
        insertVisit(ownerId, tripId, passedCell, "passed", "home-passed");
        insertVisit(ownerId, tripId, interpolatedCell, "passed", "home-interpolated");
        jdbc.sql("""
                UPDATE visits
                SET is_interpolated = TRUE
                WHERE trip_id = :tripId
                  AND payload_fingerprint = :fingerprint
                """)
            .param("tripId", tripId)
            .param("fingerprint", fingerprint("home-interpolated"))
            .update();

        mockMvc.perform(get("/trips/me/home")
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visited_cell_count").value(1));
    }

    @Test
    void authenticatedMembersCanDiscoverParticipantsAndOwnerCanRemoveThem() throws Exception {
        long ownerId = user("endpoint-member-owner");
        long memberId = user("endpoint-member");
        long outsiderId = user("endpoint-member-outsider");
        long tripId = trip(ownerId, "endpoint members");
        TripMembershipResponses.InviteLink invite = memberships.createInvite(ownerId, tripId);
        memberships.join(memberId, invite.token());

        mockMvc.perform(get("/trips/{tripId}/members", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(memberId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewer_id").value(memberId))
            .andExpect(jsonPath("$.owner_id").value(ownerId))
            .andExpect(jsonPath("$.members.length()").value(2))
            .andExpect(jsonPath("$.members[*].nickname").value(containsInAnyOrder(
                "endpoint-member-owner",
                "endpoint-member"
            )));
        mockMvc.perform(get("/trips/{tripId}/members", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(outsiderId)))))
            .andExpect(status().isForbidden());

        mockMvc.perform(delete("/trips/{tripId}/members/{userId}", tripId, memberId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/trips/{tripId}/members", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(memberId)))))
            .andExpect(status().isForbidden());
    }

    @Test
    void endedTripArchiveAggregatesRetainedJourneyRowsForMembersOnly() throws Exception {
        long ownerId = user("archive-owner");
        long otherId = user("archive-other");
        TripResponses.Created trip = planningTrip(ownerId, "archive journey");
        link(ownerId, trip.id(), "archive place");
        long basketItemId = basketItem(trip.id());
        mockMvc.perform(put("/trips/{tripId}/itinerary", trip.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[{
                      "basket_item_id": %d,
                      "day_number": 1,
                      "order_index": 0,
                      "planned_arrival": null,
                      "planned_duration_min": null
                    }]}
                    """.formatted(basketItemId)))
            .andExpect(status().isOk());
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        insertVisit(ownerId, trip.id(), cellId, "visited", "a");
        insertVisit(ownerId, trip.id(), cellId, "passed", "b");
        jdbc.sql("UPDATE visits SET is_interpolated = TRUE WHERE trip_id = :tripId AND payload_fingerprint = :fingerprint")
            .param("tripId", trip.id())
            .param("fingerprint", fingerprint("b"))
            .update();
        jdbc.sql("""
                INSERT INTO photos (trip_id, user_id, source, original_key, thumb_key)
                VALUES (:tripId, :userId, 'camera', :originalKey, :thumbKey)
                """)
            .param("tripId", trip.id())
            .param("userId", ownerId)
            .param("originalKey", "task12/archive-original.jpg")
            .param("thumbKey", "task12/archive-thumb.jpg")
            .update();

        mockMvc.perform(get("/trips/{tripId}/archive", trip.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isConflict());
        mockMvc.perform(patch("/trips/{tripId}/mode", trip.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ended\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/trips/{tripId}/archive", trip.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("ended"))
            .andExpect(jsonPath("$.itinerary_item_count").value(1))
            .andExpect(jsonPath("$.itinerary_change_count").value(1))
            .andExpect(jsonPath("$.visit_count").value(2))
            .andExpect(jsonPath("$.visited_count").value(1))
            .andExpect(jsonPath("$.passed_count").value(1))
            .andExpect(jsonPath("$.visited_cell_count").value(1))
            .andExpect(jsonPath("$.photo_count").value(1))
            .andExpect(jsonPath("$.trail.length()").value(2))
            .andExpect(jsonPath("$.trail[0].user_id").value(ownerId))
            .andExpect(jsonPath("$.trail[0].cell_id").value(CellIdCalculator.toWire(cellId)))
            .andExpect(jsonPath("$.trail[0].latitude").value(35.815))
            .andExpect(jsonPath("$.trail[0].longitude").value(127.15))
            .andExpect(jsonPath("$.trail[0].status").value("visited"))
            .andExpect(jsonPath("$.trail[0].is_interpolated").value(false))
            .andExpect(jsonPath("$.trail[1].status").value("passed"))
            .andExpect(jsonPath("$.trail[1].is_interpolated").value(true))
            .andExpect(jsonPath("$.trail[0].visit_id").isNumber())
            .andExpect(jsonPath("$.trail[1].visit_id").isNumber());
        mockMvc.perform(get("/trips/{tripId}/archive", trip.id())
                .with(jwt().jwt(token -> token.subject(Long.toString(otherId)))))
            .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanLinkItemsAndReplaceOnlyTheirTripItinerary() throws Exception {
        long ownerId = user("owner");
        long otherId = user("other");
        long tripId = trip(ownerId, "내 여행");
        long otherTripId = trip(otherId, "다른 여행");

        link(ownerId, tripId, "공유 장소");
        String unauthorizedKey = "endpoint-unauthorized-" + tripId;
        String unauthorizedFingerprint = BasketPayloadFingerprint.forLink(
            tripId, "naver", "https://map.naver.com/example", "권한 없는 장소", null
        );
        mockMvc.perform(post("/basket-items/link")
                .with(jwt().jwt(token -> token.subject(Long.toString(otherId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trip_id": %d,
                      "client_item_id": "%s",
                      "payload_fingerprint": "%s",
                      "source": "naver",
                      "original_url": "https://map.naver.com/example",
                      "title": "권한 없는 장소"
                    }
                    """.formatted(tripId, unauthorizedKey, unauthorizedFingerprint)))
            .andExpect(status().isForbidden());
        link(otherId, otherTripId, "다른 장소");
        long basketItemId = basketItem(tripId);
        long otherBasketItemId = basketItem(otherTripId);

        mockMvc.perform(get("/trips/{tripId}/basket", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(basketItemId))
            .andExpect(jsonPath("$[0].item_type").value("link"))
            .andExpect(jsonPath("$[0].source").value("naver"));
        mockMvc.perform(get("/trips/{tripId}/basket", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(otherId)))))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "items": [{
                        "basket_item_id": %d,
                        "day_number": 1,
                        "order_index": 0,
                        "planned_arrival": "09:30",
                        "planned_duration_min": 45
                      }]
                    }
                    """.formatted(basketItemId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].basket_item_id").value(basketItemId))
            .andExpect(jsonPath("$.items[0].planned_arrival").value("09:30"))
            .andExpect(jsonPath("$.items[0].planned_duration_min").value(45));
        String changePayload = jdbc.sql("""
                SELECT payload::text
                FROM itinerary_changes
                WHERE trip_id = :tripId
                """)
            .param("tripId", tripId)
            .query(String.class)
            .single();
        assertThat(changePayload)
            .contains("before", "after", Long.toString(basketItemId));

        mockMvc.perform(get("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].basket_item_id").value(basketItemId))
            .andExpect(jsonPath("$.items[0].day_number").value(1))
            .andExpect(jsonPath("$.items[0].order_index").value(0))
            .andExpect(jsonPath("$.items[0].planned_arrival").value("09:30"))
            .andExpect(jsonPath("$.items[0].planned_duration_min").value(45));
        mockMvc.perform(get("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(otherId)))))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(otherId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + "\"items\":[]}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "items": [{
                        "basket_item_id": %d,
                        "day_number": 1,
                        "order_index": 0,
                        "planned_arrival": null,
                        "planned_duration_min": null
                      }]
                    }
                    """.formatted(otherBasketItemId)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/trips/{tripId}/itinerary", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].basket_item_id").value(basketItemId));
    }

    private void link(long userId, long tripId, String title) throws Exception {
        String clientItemId = "endpoint-" + userId + "-" + tripId + "-" + title;
        String fingerprint = BasketPayloadFingerprint.forLink(
            tripId, "naver", "https://map.naver.com/example", title, null
        );
        mockMvc.perform(post("/basket-items/link")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trip_id": %d,
                      "client_item_id": "%s",
                      "payload_fingerprint": "%s",
                      "source": "naver",
                      "original_url": "https://map.naver.com/example",
                      "title": "%s",
                      "category": null
                    }
                    """.formatted(tripId, clientItemId, fingerprint, title)))
            .andExpect(status().isOk());
    }

    private TripResponses.Created planningTrip(long ownerId, String title) {
        return planning.createTrip(
            ownerId,
            new TripRequests.Create(
                title,
                "tour",
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 9, 3)
            )
        );
    }

    private void insertVisit(
        long userId,
        long tripId,
        long cellId,
        String status,
        String marker
    ) {
        Instant enteredAt = Instant.parse("2026-09-01T09:00:00Z");
        jdbc.sql("""
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint, cell_id,
                    lat, lng, entered_at, left_at, status
                ) VALUES (
                    :userId, :tripId, :clientVisitId, :fingerprint, :cellId,
                    35.815, 127.15, :enteredAt, :leftAt, :status
                )
                """)
            .param("userId", userId)
            .param("tripId", tripId)
            .param("clientVisitId", UUID.randomUUID())
            .param("fingerprint", fingerprint(marker))
            .param("cellId", cellId)
            .param("enteredAt", java.sql.Timestamp.from(enteredAt))
            .param("leftAt", java.sql.Timestamp.from(enteredAt.plusSeconds(1800)))
            .param("status", status)
            .update();
    }

    private String fingerprint(String marker) {
        return "%064x".formatted(marker.hashCode());
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId, String title) {
        long tripId = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, :title, 'tour')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", title)
            .query(Long.class)
            .single();
        jdbc.sql("INSERT INTO trip_itinerary_states (trip_id) VALUES (:tripId)")
            .param("tripId", tripId)
            .update();
        return tripId;
    }

    private long basketItem(long tripId) {
        return jdbc.sql("""
                SELECT id
                FROM basket_items
                WHERE trip_id = :tripId
                ORDER BY id
                """)
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    private PlaceCandidate candidate(String externalId, String name) {
        return new PlaceCandidate(
            "google",
            externalId,
            name,
            "전북 전주시",
            35.815,
            127.15,
            List.of("cafe"),
            List.of(),
            null,
            null,
            null,
            List.of()
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGooglePlacesConfiguration {
        @Bean
        @Primary
        GooglePlacesClient googlePlacesClient() {
            return mock(GooglePlacesClient.class);
        }
    }
}
