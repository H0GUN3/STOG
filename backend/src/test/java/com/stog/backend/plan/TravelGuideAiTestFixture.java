package com.stog.backend.plan;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class TravelGuideAiTestFixture {
    private final JdbcClient jdbc;

    TravelGuideAiTestFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Fixture create() {
        long ownerId = user("proposal-owner");
        long tripId = trip(ownerId, "제안 여행");
        long fixedItemId = basket(ownerId, tripId, "고정 장소", "resolved", true);
        long resolvedItemId = basket(ownerId, tripId, "다음 장소", "manual", true);
        long unresolvedItemId = basket(ownerId, tripId, "위치 미확인", "unresolved", false);
        jdbc.sql(
                """
                INSERT INTO itinerary_items (
                    trip_id, basket_item_id, day_number, order_index, planned_arrival,
                    planned_duration_min, is_fixed
                ) VALUES (:tripId, :basketItemId, 1, 0, '09:00', 60, TRUE)
                """
            )
            .param("tripId", tripId)
            .param("basketItemId", fixedItemId)
            .update();
        return new Fixture(ownerId, tripId, fixedItemId, resolvedItemId, unresolvedItemId);
    }

    ConcurrentFixture createConcurrent() {
        long ownerId = user("proposal-concurrency");
        long tripId = trip(ownerId, "동시 적용");
        long basketItemId = basket(ownerId, tripId, "동시 장소", "resolved", true);
        return new ConcurrentFixture(ownerId, tripId, basketItemId);
    }

    long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    long trip(long ownerId, String title) {
        long id = jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type, planned_start_date, planned_end_date)
                VALUES (:ownerId, :title, 'tour', DATE '2026-09-01', DATE '2026-09-01') RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .param("title", title)
            .query(Long.class)
            .single();
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", id)
            .param("userId", ownerId)
            .update();
        jdbc.sql("INSERT INTO trip_itinerary_states (trip_id) VALUES (:tripId)")
            .param("tripId", id)
            .update();
        return id;
    }

    long count(String table, long tripId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    private long basket(long userId, long tripId, String title, String status, boolean resolved) {
        String normalizedName = "proposal" + UUID.randomUUID().toString().replace("-", "");
        Long placeId = resolved ? jdbc.sql(
                """
                INSERT INTO places (name, category, lat, lng, source, normalized_name, compact_name)
                VALUES (:title, 'CAFE', 35.8, 127.1, 'user', :normalizedName, :normalizedName) RETURNING id
                """
            )
            .param("title", title)
            .param("normalizedName", normalizedName)
            .query(Long.class)
            .single() : null;
        return jdbc.sql(
                """
                INSERT INTO basket_items (
                    trip_id, added_by, item_type, place_id, source, original_url, title,
                    lat, lng, status, client_item_id, payload_fingerprint
                ) VALUES (
                    :tripId, :userId, :itemType, :placeId, 'user', :url, :title,
                    :lat, :lng, :status, :clientItemId, repeat('a', 64)
                ) RETURNING id
                """
            )
            .param("tripId", tripId)
            .param("userId", userId)
            .param("itemType", resolved ? "place" : "link")
            .param("placeId", placeId)
            .param("url", resolved ? null : "https://example.test/" + UUID.randomUUID())
            .param("title", title)
            .param("lat", resolved ? 35.8 : null)
            .param("lng", resolved ? 127.1 : null)
            .param("status", status)
            .param("clientItemId", UUID.randomUUID().toString())
            .query(Long.class)
            .single();
    }

    static String previewBody(Fixture fixture) {
        return """
            {
              "base_version":0,
              "day_windows":[{"day_number":1,"start":"09:00","end":"18:00"}],
              "actions":[
                {"basket_item_id":%d,"day_number":1,"order_index":2,"planned_arrival":"10:31","planned_duration_min":10,"travel_minutes_from_previous":999,"is_fixed":false},
                {"basket_item_id":%d,"day_number":1,"order_index":1,"planned_arrival":"10:30","planned_duration_min":45,"travel_minutes_from_previous":30,"is_fixed":false},
                {"basket_item_id":%d,"day_number":1,"order_index":0,"planned_arrival":"09:00","planned_duration_min":60,"travel_minutes_from_previous":0,"is_fixed":true}
              ]
            }
            """.formatted(
                fixture.unresolvedItemId(), fixture.resolvedItemId(), fixture.fixedItemId()
            );
    }

    static String applyBody(String clientApplyId, String fingerprint) {
        return """
            {"client_apply_id":"%s","proposal_fingerprint":"%s"}
            """.formatted(clientApplyId, fingerprint);
    }

    record Fixture(
        long ownerId,
        long tripId,
        long fixedItemId,
        long resolvedItemId,
        long unresolvedItemId
    ) {
    }

    record ConcurrentFixture(long ownerId, long tripId, long basketItemId) {
    }
}
