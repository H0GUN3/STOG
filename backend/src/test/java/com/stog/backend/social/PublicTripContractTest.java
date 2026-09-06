package com.stog.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PublicTripContractTest {
    private static final Instant ENDED_AT = Instant.parse("2042-04-03T12:00:00Z");
    private static final long CELL_ID = CellIdCalculator.fromCoords(35.815, 127.15);

    @Autowired private PublicTripFeedService feed;
    @Autowired private PublicTripLikeService likes;
    @Autowired private PublicTripCopyService copies;
    @Autowired private PublicTripRepository trips;
    @Autowired private JdbcClient jdbc;

    private long sequence;

    @Test
    void exposesOnlyPublicEndedTripsWithOrderedPerMemberTrails() {
        long owner = user("owner");
        long member = user("member");
        long eligible = trip(owner, "eligible", "public", "ended", ENDED_AT);
        trip(owner, "public-active", "public", "active", null);
        trip(owner, "private-ended", "private", "ended", ENDED_AT.plusSeconds(1));
        long missingEnd = trip(owner, "missing-end", "public", "ended", ENDED_AT.plusSeconds(2));
        jdbc.sql("UPDATE trips SET ended_at = NULL WHERE id = :tripId")
            .param("tripId", missingEnd).update();
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", eligible).param("userId", member).update();

        visit(eligible, owner, ENDED_AT.minusSeconds(40), "owner-later");
        visit(eligible, owner, ENDED_AT.minusSeconds(80), "owner-first");
        visit(eligible, member, ENDED_AT.minusSeconds(60), "member");

        PublicTripResponses.Page page = feed.get(null, null, 20);
        PublicTripResponses.Item item = page.items().stream()
            .filter(value -> value.trip_id() == eligible).findFirst().orElseThrow();

        assertThat(page.items()).extracting(PublicTripResponses.Item::title)
            .contains("eligible")
            .doesNotContain("public-active", "private-ended", "missing-end");
        assertThat(item.member_trails()).extracting(PublicTripResponses.MemberTrail::user_id)
            .containsExactly(owner, member);
        assertThat(item.member_trails().get(0).visits())
            .extracting(PublicTripResponses.Visit::entered_at)
            .isSorted();
        assertThat(item.member_trails().get(0).visits())
            .extracting(PublicTripResponses.Visit::visit_id)
            .hasSize(2);
    }

    @Test
    void batchesVisitLookupWithoutChangingVisitOrdering() {
        long owner = user("batch-owner");
        long firstTrip = trip(owner, "batch-first", "public", "ended", ENDED_AT);
        long secondTrip = trip(owner, "batch-second", "public", "ended", ENDED_AT);

        long firstVisit = visit(firstTrip, owner, ENDED_AT.minusSeconds(20), "batch-first");
        long secondVisit = visit(firstTrip, owner, ENDED_AT.minusSeconds(10), "batch-second");
        visit(secondTrip, owner, ENDED_AT.minusSeconds(10), "batch-third");

        var visitsByTrip = trips.findVisitsByTripIds(List.of(firstTrip, secondTrip));

        assertThat(visitsByTrip.get(firstTrip))
            .extracting(PublicTripRepository.VisitRow::id)
            .containsExactly(firstVisit, secondVisit);
        assertThat(visitsByTrip.get(firstTrip))
            .extracting(PublicTripRepository.VisitRow::enteredAt)
            .isSorted();
        assertThat(visitsByTrip.get(secondTrip))
            .extracting(PublicTripRepository.VisitRow::id)
            .hasSize(1);
    }

    @Test
    void tripLikeAndUnlikeAreIdempotentAndRejectIneligibleTargets() {
        long owner = user("owner-like");
        long viewer = user("viewer-like");
        long trip = trip(owner, "likeable", "public", "ended", ENDED_AT);
        long privateTrip = trip(owner, "not-likeable", "private", "ended", ENDED_AT);

        assertThat(likes.like(viewer, trip))
            .isEqualTo(new PublicTripResponses.LikeState(trip, 1, true));
        assertThat(likes.like(viewer, trip))
            .isEqualTo(new PublicTripResponses.LikeState(trip, 1, true));
        assertThat(likes.unlike(viewer, trip))
            .isEqualTo(new PublicTripResponses.LikeState(trip, 0, false));
        assertThat(likes.unlike(viewer, trip))
            .isEqualTo(new PublicTripResponses.LikeState(trip, 0, false));
        assertThatThrownBy(() -> likes.like(viewer, privateTrip))
            .isInstanceOf(PublicTripApiException.class)
            .extracting(error -> ((PublicTripApiException) error).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void copiesOrderedItemsOnceAndConflictsWhenAKeyIsReusedForAnotherDestinationDay() {
        long user = user("copy-user");
        long source = trip(user, "source", "public", "ended", ENDED_AT);
        long destination = trip(user, "destination", "private", "dormant", null);
        sourceItem(source, 2, 0, "second-day-first");
        sourceItem(source, 1, 0, "first-day-first");

        PublicTripRequests.Copy request = new PublicTripRequests.Copy(destination, 2, "copy-key");
        PublicTripResponses.CopyResult first = copies.copy(user, source, request);
        PublicTripResponses.CopyResult replay = copies.copy(user, source, request);

        assertThat(replay).isEqualTo(first);
        assertThat(first.copied_item_count()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM public_trip_copy_receipts WHERE user_id = :userId")
            .param("userId", user).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT basket.title FROM itinerary_items itinerary
                JOIN basket_items basket ON basket.id = itinerary.basket_item_id
                WHERE itinerary.trip_id = :tripId AND itinerary.day_number = 2
                ORDER BY itinerary.order_index
                """)
            .param("tripId", destination).query(String.class).list())
            .containsExactly("first-day-first", "second-day-first");

        assertThatThrownBy(() -> copies.copy(
            user, source, new PublicTripRequests.Copy(destination, 1, "copy-key")
        ))
            .isInstanceOf(PublicTripApiException.class)
            .satisfies(error -> {
                PublicTripApiException api = (PublicTripApiException) error;
                assertThat(api.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(api.code()).isEqualTo("TRIP_COPY_IDEMPOTENCY_CONFLICT");
            });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM itinerary_items WHERE trip_id = :tripId")
            .param("tripId", destination).query(Long.class).single()).isEqualTo(2);
    }

    private long user(String name) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:name) RETURNING id")
            .param("name", "f12-" + name + "-" + (++sequence)).query(Long.class).single();
    }

    private long trip(long owner, String title, String visibility, String mode, Instant endedAt) {
        long id = jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility, mode,
                    planned_start_date, planned_end_date, ended_at)
                VALUES (:owner, :title, 'tour', :visibility, :mode, :startDate, :endDate, :endedAt)
                RETURNING id
                """)
            .param("owner", owner).param("title", title).param("visibility", visibility)
            .param("mode", mode).param("startDate", LocalDate.of(2042, 4, 1))
            .param("endDate", LocalDate.of(2042, 4, 3))
            .param("endedAt", endedAt == null ? null : Timestamp.from(endedAt))
            .query(Long.class).single();
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", id).param("userId", owner).update();
        jdbc.sql("INSERT INTO trip_itinerary_states (trip_id) VALUES (:tripId)")
            .param("tripId", id).update();
        return id;
    }

    private long visit(long trip, long user, Instant enteredAt, String key) {
        return jdbc.sql("""
                INSERT INTO visits (trip_id, user_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status, is_interpolated)
                VALUES (:tripId, :userId, :clientId, :fingerprint, :cellId, 35.815, 127.15,
                    :enteredAt, :leftAt, 'visited', FALSE)
                RETURNING id
                """)
            .param("tripId", trip).param("userId", user).param("clientId", UUID.randomUUID())
            .param("fingerprint", "a".repeat(64)).param("cellId", CELL_ID)
            .param("enteredAt", Timestamp.from(enteredAt))
            .param("leftAt", Timestamp.from(enteredAt.plusSeconds(30)))
            .query(Long.class).single();
    }

    private void sourceItem(long trip, int day, int order, String title) {
        long basket = jdbc.sql("""
                INSERT INTO basket_items (trip_id, added_by, item_type, original_url, title,
                    status, client_item_id, payload_fingerprint)
                SELECT :tripId, owner_id, 'link', :url, :title, 'unresolved', :clientId,
                       :fingerprint FROM trips WHERE id = :tripId
                RETURNING id
                """)
            .param("tripId", trip).param("url", "https://example.test/" + title)
            .param("title", title).param("clientId", "source-" + title)
            .param("fingerprint", "b".repeat(64)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO itinerary_items (trip_id, basket_item_id, day_number, order_index)
                VALUES (:tripId, :basketId, :day, :orderIndex)
                """)
            .param("tripId", trip).param("basketId", basket)
            .param("day", day).param("orderIndex", order).update();
    }
}
