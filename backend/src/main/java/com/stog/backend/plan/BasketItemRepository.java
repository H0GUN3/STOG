package com.stog.backend.plan;

import com.stog.backend.storage.GcsReadUrlSigner;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BasketItemRepository {
    private final JdbcClient jdbc;
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    @Autowired
    public BasketItemRepository(
        JdbcClient jdbc,
        ObjectProvider<GcsReadUrlSigner> signedReads
    ) {
        this.jdbc = jdbc;
        this.signedReads = signedReads;
    }

    public BasketItemRepository(JdbcClient jdbc) {
        this(jdbc, null);
    }

    public void lockIdempotencyScope(long userId, long tripId, String clientItemId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
            .param("scope", tripId + ":" + userId + ":" + clientItemId)
            .query((row, rowNumber) -> true)
            .single();
    }

    public Optional<IdempotentItem> findByClientItemId(
        long userId,
        long tripId,
        String clientItemId
    ) {
        return jdbc.sql(
                """
                SELECT id, payload_fingerprint, place_id, cell_id, status
                FROM basket_items
                WHERE trip_id = :tripId
                  AND added_by = :userId
                  AND client_item_id = :clientItemId
                """
            )
            .param("tripId", tripId)
            .param("userId", userId)
            .param("clientItemId", clientItemId)
            .query((row, rowNumber) -> new IdempotentItem(
                row.getLong("id"),
                row.getString("payload_fingerprint"),
                row.getObject("place_id", Long.class),
                row.getObject("cell_id", Long.class),
                row.getString("status")
            ))
            .optional();
    }

    public long addPlace(
        long userId,
        long tripId,
        PlaceRepository.Upserted place,
        BasketRequests.AddPlace request
    ) {
        return jdbc.sql(
                """
                INSERT INTO basket_items (
                    trip_id, added_by, item_type, place_id, source, title,
                    category, lat, lng, cell_id, status, client_item_id,
                    payload_fingerprint, provider, external_id, source_record_id,
                    source_type, source_label, confidence, address
                )
                VALUES (
                    :tripId, :userId, 'place', :placeId, :source, :title,
                    :category, :latitude, :longitude, :cellId, 'resolved',
                    :clientItemId, :payloadFingerprint, :provider, :externalId,
                    :sourceRecordId, :sourceType, :sourceLabel, 1.0, NULL
                )
                RETURNING id
                """
            )
            .param("tripId", tripId)
            .param("userId", userId)
            .param("placeId", place.id())
            .param("source", request.provider())
            .param("title", request.name())
            .param("category", request.category())
            .param("latitude", request.latitude())
            .param("longitude", request.longitude())
            .param("cellId", place.cellId())
            .param("clientItemId", request.client_item_id())
            .param("payloadFingerprint", request.payload_fingerprint())
            .param("provider", request.provider())
            .param("externalId", request.external_id())
            .param("sourceRecordId", request.canonical_source_id())
            .param("sourceType", request.provider())
            .param("sourceLabel", request.provider())
            .query(Long.class)
            .single();
    }

    public long addLink(long userId, BasketRequests.AddLink request) {
        return jdbc.sql(
                """
                INSERT INTO basket_items (
                    trip_id, added_by, item_type, source, original_url, title,
                    category, status, client_item_id, payload_fingerprint,
                    provider, external_id, source_type, source_label, confidence
                )
                VALUES (
                    :tripId, :userId, 'link', :source, :originalUrl, :title,
                    :category, 'unresolved', :clientItemId, :payloadFingerprint,
                    :source, :originalUrl, 'link', :source, 1.0
                )
                RETURNING id
                """
            )
            .param("tripId", request.trip_id())
            .param("userId", userId)
            .param("source", request.source())
            .param("originalUrl", request.original_url())
            .param("title", request.title())
            .param("category", request.category())
            .param("clientItemId", request.client_item_id())
            .param("payloadFingerprint", request.payload_fingerprint())
            .query(Long.class)
            .single();
    }

    public List<BasketResponses.Item> findByTrip(long tripId) {
        return jdbc.sql(
                """
                SELECT basket.id,
                       basket.item_type,
                       basket.title,
                       basket.category,
                       basket.source,
                       basket.status,
                       COALESCE(
                           NULLIF(basket.thumbnail_url, ''),
                           image.source_url
                       ) AS image_url,
                       image.object_key AS image_object_key,
                       basket.added_at
                FROM basket_items basket
                LEFT JOIN LATERAL (
                    SELECT source_image.source_url, source_image.object_key
                    FROM place_source_records source_record
                    JOIN place_source_images source_image
                      ON source_image.place_source_record_id = source_record.id
                     AND source_image.reusable
                    JOIN license_snapshots license
                      ON license.id = source_image.license_snapshot_id
                     AND license.allows_public_discovery
                     AND jsonb_exists(license.reusable_fields, 'image')
                    WHERE source_record.place_id = basket.place_id
                      AND source_record.active
                    ORDER BY source_image.id
                    LIMIT 1
                ) image ON TRUE
                WHERE basket.trip_id = :tripId
                ORDER BY basket.added_at, basket.id
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new BasketResponses.Item(
                row.getLong("id"),
                row.getString("item_type"),
                row.getString("title"),
                row.getString("category"),
                row.getString("source"),
                row.getString("status"),
                CatalogImageUrl.resolve(
                    row.getString("image_url"),
                    row.getString("image_object_key"),
                    signedReads == null ? null : signedReads.getIfAvailable()
                ),
                row.getObject("added_at", OffsetDateTime.class).toInstant()
            ))
            .list();
    }

    public boolean allBelongToTrip(long tripId, List<Long> basketItemIds) {
        if (basketItemIds.isEmpty()) {
            return true;
        }
        long matching = jdbc.sql(
                """
                SELECT COUNT(*)
                FROM basket_items
                WHERE trip_id = :tripId
                  AND id IN (:basketItemIds)
                """
            )
            .param("tripId", tripId)
            .param("basketItemIds", basketItemIds)
            .query(Long.class)
            .single();
        return matching == basketItemIds.size();
    }

    public boolean existsInTrip(long tripId, long basketItemId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM basket_items
                    WHERE trip_id = :tripId
                      AND id = :basketItemId
                )
                """)
            .param("tripId", tripId)
            .param("basketItemId", basketItemId)
            .query(Boolean.class)
            .single();
    }

    public boolean isFixedInItinerary(long tripId, long basketItemId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM itinerary_items
                    WHERE trip_id = :tripId
                      AND basket_item_id = :basketItemId
                      AND is_fixed
                )
                """)
            .param("tripId", tripId)
            .param("basketItemId", basketItemId)
            .query(Boolean.class)
            .single();
    }

    public boolean deleteFromTrip(long tripId, long basketItemId) {
        return jdbc.sql("""
                DELETE FROM basket_items
                WHERE trip_id = :tripId
                  AND id = :basketItemId
                """)
            .param("tripId", tripId)
            .param("basketItemId", basketItemId)
            .update() == 1;
    }

    public record IdempotentItem(
        long id,
        String payloadFingerprint,
        Long placeId,
        Long cellId,
        String status
    ) {
    }
}
