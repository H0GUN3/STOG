package com.stog.backend.social;

import com.stog.backend.cell.CellIdCalculator;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublicTripRepository {
    private final JdbcClient jdbc;

    public PublicTripRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TripRow> findTrips(Long viewerId, PublicTripCursor cursor, int limit) {
        String liked = viewerId == null ? "FALSE" : """
            EXISTS (SELECT 1 FROM likes viewer_like
                    WHERE viewer_like.user_id = :viewerId
                      AND viewer_like.target_type = 'trip'
                      AND viewer_like.target_id = trip.id)
            """;
        String after = cursor == null ? "" : """
            AND (trip.ended_at < :endedAt
                 OR (trip.ended_at = :endedAt AND trip.id < :tripId))
            """;
        JdbcClient.StatementSpec statement = jdbc.sql("""
            SELECT trip.id, trip.owner_id, trip.title, trip.ended_at,
                   (SELECT COUNT(*) FROM likes trip_like
                    WHERE trip_like.target_type = 'trip'
                      AND trip_like.target_id = trip.id) AS like_count,
                   %s AS liked_by_viewer,
                   (SELECT COUNT(*) FROM itinerary_items itinerary
                    WHERE itinerary.trip_id = trip.id) AS itinerary_item_count
            FROM trips trip
            WHERE trip.visibility = 'public'
              AND trip.mode = 'ended'
              AND trip.ended_at IS NOT NULL
              %s
            ORDER BY trip.ended_at DESC, trip.id DESC
            LIMIT :limit
            """.formatted(liked, after)).param("limit", limit);
        if (viewerId != null) statement = statement.param("viewerId", viewerId);
        if (cursor != null) {
            statement = statement.param("endedAt", Timestamp.from(cursor.endedAt()))
                .param("tripId", cursor.tripId());
        }
        return statement.query((row, number) -> new TripRow(
            row.getLong("id"), row.getLong("owner_id"), row.getString("title"),
            row.getObject("ended_at", OffsetDateTime.class).toInstant(),
            row.getLong("like_count"), row.getBoolean("liked_by_viewer"),
            row.getLong("itinerary_item_count")
        )).list();
    }

    public List<VisitRow> findVisits(long tripId) {
        return findVisitsByTripIds(List.of(tripId)).getOrDefault(tripId, List.of());
    }

    public Map<Long, List<VisitRow>> findVisitsByTripIds(List<Long> tripIds) {
        if (tripIds.isEmpty()) return Map.of();
        Map<Long, List<VisitRow>> visitsByTrip = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT trip_id, id, user_id, cell_id, lat, lng, entered_at, left_at,
                       status, is_interpolated
                FROM visits
                WHERE trip_id IN (:tripIds)
                ORDER BY trip_id, user_id, entered_at, id
                """)
            .param("tripIds", tripIds)
            .query((row, number) -> new TripVisitRow(
                row.getLong("trip_id"),
                new VisitRow(
                    row.getLong("id"), row.getLong("user_id"),
                    CellIdCalculator.toWire(row.getLong("cell_id")),
                    row.getDouble("lat"), row.getDouble("lng"),
                    row.getObject("entered_at", OffsetDateTime.class).toInstant(),
                    row.getObject("left_at", OffsetDateTime.class).toInstant(),
                    row.getString("status"), row.getBoolean("is_interpolated")
                )
            ))
            .list()
            .forEach(row -> visitsByTrip
                .computeIfAbsent(row.tripId(), ignored -> new ArrayList<>())
                .add(row.visit()));
        visitsByTrip.replaceAll((tripId, visits) -> List.copyOf(visits));
        return Map.copyOf(visitsByTrip);
    }

    public boolean isEligible(long tripId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM trips
                              WHERE id = :tripId AND visibility = 'public'
                                AND mode = 'ended' AND ended_at IS NOT NULL)
                """)
            .param("tripId", tripId).query(Boolean.class).single();
    }

    public void lockEligible(long tripId) {
        boolean eligible = jdbc.sql("""
                SELECT visibility = 'public' AND mode = 'ended' AND ended_at IS NOT NULL
                FROM trips WHERE id = :tripId FOR SHARE
                """)
            .param("tripId", tripId).query(Boolean.class).optional().orElse(false);
        if (!eligible) throw new PublicTripApiException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "PUBLIC_TRIP_NOT_FOUND", "Eligible public trip not found"
        );
    }

    public void lockCopyScope(long userId, String idempotencyKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
            .param("scope", userId + ":" + idempotencyKey)
            .query((row, rowNumber) -> true).single();
    }

    public Optional<CopyReceipt> findReceipt(long userId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT source_trip_id, destination_trip_id, destination_day,
                       payload_fingerprint, copied_item_count,
                       destination_basket_item_ids, destination_itinerary_item_ids,
                       itinerary_change_id
                FROM public_trip_copy_receipts
                WHERE user_id = :userId AND idempotency_key = :idempotencyKey
                """)
            .param("userId", userId).param("idempotencyKey", idempotencyKey)
            .query((row, number) -> new CopyReceipt(
                row.getLong("source_trip_id"), row.getLong("destination_trip_id"),
                row.getInt("destination_day"), row.getString("payload_fingerprint"),
                row.getInt("copied_item_count"), longs(row.getArray("destination_basket_item_ids")),
                longs(row.getArray("destination_itinerary_item_ids")),
                row.getLong("itinerary_change_id")
            )).optional();
    }

    public void lockDestinationItinerary(long tripId) {
        jdbc.sql("SELECT version FROM trip_itinerary_states WHERE trip_id = :tripId FOR UPDATE")
            .param("tripId", tripId).query(Long.class).single();
    }

    public int nextOrder(long tripId, int day) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(order_index) + 1, 0)
                FROM itinerary_items WHERE trip_id = :tripId AND day_number = :day
                """)
            .param("tripId", tripId).param("day", day).query(Integer.class).single();
    }

    public List<SourceItem> sourceItems(long tripId) {
        return jdbc.sql("""
                SELECT itinerary.id AS itinerary_id, itinerary.basket_item_id,
                       itinerary.planned_arrival, itinerary.planned_duration_min,
                       basket.item_type, basket.place_id, basket.source, basket.original_url,
                       basket.title, basket.thumbnail_url, basket.category, basket.lat, basket.lng,
                       basket.cell_id, basket.status, basket.provider, basket.external_id,
                       basket.source_record_id, basket.source_type, basket.source_label,
                       basket.confidence, basket.address
                FROM itinerary_items itinerary
                JOIN basket_items basket ON basket.id = itinerary.basket_item_id
                                       AND basket.trip_id = itinerary.trip_id
                WHERE itinerary.trip_id = :tripId
                ORDER BY itinerary.day_number, itinerary.order_index, itinerary.id
                """)
            .param("tripId", tripId)
            .query((row, number) -> new SourceItem(
                row.getLong("itinerary_id"), row.getLong("basket_item_id"),
                row.getObject("planned_arrival", java.sql.Time.class),
                row.getObject("planned_duration_min", Integer.class),
                row.getString("item_type"), row.getObject("place_id", Long.class),
                row.getString("source"), row.getString("original_url"), row.getString("title"),
                row.getString("thumbnail_url"), row.getString("category"),
                row.getObject("lat", Double.class), row.getObject("lng", Double.class),
                row.getObject("cell_id", Long.class), row.getString("status"),
                row.getString("provider"), row.getString("external_id"),
                row.getObject("source_record_id", Long.class), row.getString("source_type"),
                row.getString("source_label"), row.getObject("confidence", Double.class),
                row.getString("address")
            )).list();
    }

    public long copyBasketItem(
        long userId, long destinationTripId, SourceItem source,
        String clientItemId, String payloadFingerprint
    ) {
        return jdbc.sql("""
                INSERT INTO basket_items (
                    trip_id, added_by, item_type, place_id, source, original_url, title,
                    thumbnail_url, category, lat, lng, cell_id, status, client_item_id,
                    payload_fingerprint, provider, external_id, source_record_id, source_type,
                    source_label, confidence, address)
                VALUES (:tripId, :userId, :itemType, :placeId, :source, :originalUrl, :title,
                    :thumbnailUrl, :category, :lat, :lng, :cellId, :status, :clientItemId,
                    :fingerprint, :provider, :externalId, :sourceRecordId, :sourceType,
                    :sourceLabel, :confidence, :address)
                RETURNING id
                """)
            .param("tripId", destinationTripId).param("userId", userId)
            .param("itemType", source.itemType()).param("placeId", source.placeId())
            .param("source", source.source()).param("originalUrl", source.originalUrl())
            .param("title", source.title()).param("thumbnailUrl", source.thumbnailUrl())
            .param("category", source.category()).param("lat", source.lat()).param("lng", source.lng())
            .param("cellId", source.cellId()).param("status", source.status())
            .param("clientItemId", clientItemId).param("fingerprint", payloadFingerprint)
            .param("provider", source.provider()).param("externalId", source.externalId())
            .param("sourceRecordId", source.sourceRecordId()).param("sourceType", source.sourceType())
            .param("sourceLabel", source.sourceLabel()).param("confidence", source.confidence())
            .param("address", source.address()).query(Long.class).single();
    }

    public long addItineraryItem(
        long destinationTripId, long basketItemId, int day, int order, SourceItem source
    ) {
        return jdbc.sql("""
                INSERT INTO itinerary_items (trip_id, basket_item_id, day_number, order_index,
                    planned_arrival, planned_duration_min, is_fixed)
                VALUES (:tripId, :basketItemId, :day, :orderIndex,
                    :plannedArrival, :plannedDurationMin, FALSE)
                RETURNING id
                """)
            .param("tripId", destinationTripId).param("basketItemId", basketItemId)
            .param("day", day).param("orderIndex", order)
            .param("plannedArrival", source.plannedArrival())
            .param("plannedDurationMin", source.plannedDurationMin())
            .query(Long.class).single();
    }

    public long recordChange(
        long userId, long sourceTripId, long destinationTripId, int destinationDay, int copiedCount
    ) {
        return jdbc.sql("""
                INSERT INTO itinerary_changes (trip_id, user_id, action, payload)
                VALUES (:destinationTripId, :userId, 'copy_public_trip',
                    jsonb_build_object('source_trip_id', :sourceTripId,
                        'destination_day', :destinationDay, 'copied_item_count', :copiedCount))
                RETURNING id
                """)
            .param("destinationTripId", destinationTripId).param("userId", userId)
            .param("sourceTripId", sourceTripId).param("destinationDay", destinationDay)
            .param("copiedCount", copiedCount).query(Long.class).single();
    }

    public void saveReceipt(
        long userId, String idempotencyKey, String fingerprint,
        PublicTripResponses.CopyResult result
    ) {
        jdbc.sql("""
                INSERT INTO public_trip_copy_receipts (
                    user_id, idempotency_key, source_trip_id, destination_trip_id,
                    destination_day, payload_fingerprint, copied_item_count,
                    destination_basket_item_ids, destination_itinerary_item_ids,
                    itinerary_change_id)
                VALUES (:userId, :idempotencyKey, :sourceTripId, :destinationTripId,
                    :destinationDay, :fingerprint, :copiedCount,
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:basketIds AS jsonb))::bigint),
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:itineraryIds AS jsonb))::bigint),
                    :changeId)
                """)
            .param("userId", userId).param("idempotencyKey", idempotencyKey)
            .param("sourceTripId", result.source_trip_id())
            .param("destinationTripId", result.destination_trip_id())
            .param("destinationDay", result.destination_day()).param("fingerprint", fingerprint)
            .param("copiedCount", result.copied_item_count())
            .param("basketIds", result.destination_basket_item_ids().toString())
            .param("itineraryIds", result.destination_itinerary_item_ids().toString())
            .param("changeId", result.itinerary_change_id()).update();
    }

    private static List<Long> longs(Array array) throws java.sql.SQLException {
        Object[] values = (Object[]) array.getArray();
        List<Long> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(((Number) value).longValue());
        return List.copyOf(result);
    }

    public record TripRow(long id, long ownerId, String title, Instant endedAt,
                          long likeCount, boolean likedByViewer, long itineraryItemCount) {}
    public record VisitRow(long id, long userId, String cellId, double lat, double lng,
                           Instant enteredAt, Instant leftAt, String status, boolean interpolated) {}
    private record TripVisitRow(long tripId, VisitRow visit) {}
    public record CopyReceipt(long sourceTripId, long destinationTripId, int destinationDay,
                              String fingerprint, int copiedCount, List<Long> basketIds,
                              List<Long> itineraryIds, long changeId) {}
    public record SourceItem(long itineraryId, long basketItemId, java.sql.Time plannedArrival,
                             Integer plannedDurationMin, String itemType, Long placeId, String source,
                             String originalUrl, String title, String thumbnailUrl, String category,
                             Double lat, Double lng, Long cellId, String status, String provider,
                             String externalId, Long sourceRecordId, String sourceType,
                             String sourceLabel, Double confidence, String address) {}
}
