package com.stog.backend.plan;

import com.stog.backend.storage.GcsReadUrlSigner;
import java.sql.Time;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ItineraryRepository {
    private static final DateTimeFormatter API_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbc;
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    @Autowired
    public ItineraryRepository(
        JdbcClient jdbc,
        ObjectProvider<GcsReadUrlSigner> signedReads
    ) {
        this.jdbc = jdbc;
        this.signedReads = signedReads;
    }

    public ItineraryRepository(JdbcClient jdbc) {
        this(jdbc, null);
    }

    public List<ItineraryResponses.Item> findByTrip(long tripId) {
        return jdbc.sql(
                """
                SELECT itinerary.basket_item_id,
                       basket.place_id,
                       basket.title,
                       basket.category,
                       basket.lat,
                       basket.lng,
                       basket.address,
                       COALESCE(
                           NULLIF(basket.thumbnail_url, ''),
                           image.source_url
                       ) AS image_url,
                       image.object_key AS image_object_key,
                       itinerary.day_number,
                       itinerary.order_index,
                       itinerary.planned_arrival,
                       itinerary.planned_duration_min,
                       itinerary.is_fixed
                FROM itinerary_items itinerary
                JOIN basket_items basket
                  ON basket.trip_id = itinerary.trip_id
                 AND basket.id = itinerary.basket_item_id
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
                WHERE itinerary.trip_id = :tripId
                ORDER BY itinerary.day_number, itinerary.order_index, itinerary.id
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new ItineraryResponses.Item(
                row.getLong("basket_item_id"),
                row.getObject("place_id", Long.class),
                row.getString("title"),
                row.getString("category"),
                row.getObject("lat", Double.class),
                row.getObject("lng", Double.class),
                row.getString("address"),
                CatalogImageUrl.resolve(
                    row.getString("image_url"),
                    row.getString("image_object_key"),
                    signedReads == null ? null : signedReads.getIfAvailable()
                ),
                row.getInt("day_number"),
                row.getInt("order_index"),
                apiTime(row.getString("planned_arrival")),
                row.getObject("planned_duration_min", Integer.class),
                row.getBoolean("is_fixed")
            ))
            .list();
    }

    public List<ItineraryFeasibility.ProposedItem> findFixedByTrip(long tripId) {
        return findByTrip(tripId).stream()
            .filter(ItineraryResponses.Item::is_fixed)
            .map(item -> new ItineraryFeasibility.ProposedItem(
                item.basket_item_id(), item.day_number(), item.order_index(),
                item.planned_arrival() == null ? null : LocalTime.parse(item.planned_arrival()),
                item.planned_duration_min(), 0, true
            ))
            .toList();
    }

    public List<ItineraryResponses.Change> findChangesByTrip(long tripId) {
        return jdbc.sql("""
                SELECT id, user_id, action, payload::text AS payload, created_at
                FROM itinerary_changes
                WHERE trip_id = :tripId
                ORDER BY created_at, id
                """)
            .param("tripId", tripId)
            .query((row, rowNumber) -> new ItineraryResponses.Change(
                row.getLong("id"), row.getLong("user_id"), row.getString("action"),
                row.getString("payload"), row.getObject("created_at", OffsetDateTime.class).toInstant()
            ))
            .list();
    }

    public int dayCount(long tripId) {
        return jdbc.sql(
                """
                SELECT COALESCE((planned_end_date - planned_start_date + 1)::integer, 2147483647)
                FROM trips WHERE id = :tripId
                """
            )
            .param("tripId", tripId)
            .query(Integer.class)
            .single();
    }

    public long currentVersion(long tripId) {
        return jdbc.sql("SELECT version FROM trip_itinerary_states WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    public List<Long> fixedBasketItemIds(long tripId) {
        return jdbc.sql(
                """
                SELECT basket_item_id
                FROM itinerary_items
                WHERE trip_id = :tripId AND is_fixed = TRUE
                ORDER BY basket_item_id
                """
            )
            .param("tripId", tripId)
            .query(Long.class)
            .list();
    }

    public long lockVersion(long tripId) {
        return jdbc.sql(
                "SELECT version FROM trip_itinerary_states WHERE trip_id = :tripId FOR UPDATE"
            )
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    public List<ItineraryResponses.Item> replace(
        long userId,
        long tripId,
        List<ItineraryRequests.Item> items
    ) {
        long version = lockVersion(tripId);
        Replacement replacement = new Replacement(
            new ItineraryWriteScope(userId, tripId), items, "replace", version
        );
        return replaceAndRecord(replacement).items();
    }

    public AppliedWrite applyProposal(
        ItineraryWriteScope scope,
        List<ItineraryRequests.Item> items,
        long lockedVersion
    ) {
        return replaceAndRecord(new Replacement(scope, items, "replace", lockedVersion));
    }

    private AppliedWrite replaceAndRecord(Replacement replacement) {
        long tripId = replacement.scope().tripId();
        String before = snapshot(tripId);
        jdbc.sql("DELETE FROM itinerary_items WHERE trip_id = :tripId AND is_fixed = FALSE")
            .param("tripId", tripId)
            .update();
        for (ItineraryRequests.Item item : replacement.items()) {
            if (item.is_fixed()) {
                int updated = jdbc.sql(
                        """
                        UPDATE itinerary_items
                        SET day_number = :dayNumber,
                            order_index = :orderIndex,
                            planned_arrival = :plannedArrival,
                            planned_duration_min = :plannedDurationMin
                        WHERE trip_id = :tripId
                          AND basket_item_id = :basketItemId
                          AND is_fixed = TRUE
                        """
                    )
                    .param("tripId", tripId)
                    .param("basketItemId", item.basket_item_id())
                    .param("dayNumber", item.day_number())
                    .param("orderIndex", item.order_index())
                    .param("plannedArrival", plannedArrival(item.planned_arrival()))
                    .param("plannedDurationMin", item.planned_duration_min())
                    .update();
                if (updated == 1) {
                    continue;
                }
            }
            jdbc.sql(
                """
                INSERT INTO itinerary_items (
                        trip_id, basket_item_id, day_number, order_index, planned_arrival,
                        planned_duration_min, is_fixed
                    ) VALUES (
                        :tripId, :basketItemId, :dayNumber, :orderIndex, :plannedArrival,
                        :plannedDurationMin, :isFixed
                    )
                    """
                )
                .param("tripId", tripId)
                .param("basketItemId", item.basket_item_id())
                .param("dayNumber", item.day_number())
                .param("orderIndex", item.order_index())
                .param("plannedArrival", plannedArrival(item.planned_arrival()))
                .param("plannedDurationMin", item.planned_duration_min())
                .param("isFixed", item.is_fixed())
                .update();
        }
        String after = snapshot(tripId);
        long changeId = jdbc.sql(
                """
                INSERT INTO itinerary_changes (trip_id, user_id, action, payload)
                VALUES (:tripId, :userId, :action,
                    jsonb_build_object('before', CAST(:before AS jsonb), 'after', CAST(:after AS jsonb)))
                RETURNING id
                """
            )
            .param("tripId", tripId)
            .param("userId", replacement.scope().userId())
            .param("action", replacement.action())
            .param("before", before)
            .param("after", after)
            .query(Long.class)
            .single();
        long nextVersion = currentVersion(tripId);
        return new AppliedWrite(findByTrip(tripId), changeId, nextVersion);
    }

    private Time plannedArrival(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Time.valueOf(LocalTime.parse(value));
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planned_arrival is invalid", error);
        }
    }

    private static String apiTime(String value) {
        return value == null ? null : LocalTime.parse(value).format(API_TIME_FORMAT);
    }

    private String snapshot(long tripId) {
        return jdbc.sql(
                """
                SELECT COALESCE(jsonb_agg(jsonb_build_object(
                    'basket_item_id', basket_item_id, 'day_number', day_number,
                    'order_index', order_index, 'planned_arrival', planned_arrival,
                    'planned_duration_min', planned_duration_min, 'is_fixed', is_fixed
                ) ORDER BY day_number, order_index, id), '[]'::jsonb)::text
                FROM itinerary_items WHERE trip_id = :tripId
                """
            )
            .param("tripId", tripId)
            .query(String.class)
            .single();
    }

    public record AppliedWrite(List<ItineraryResponses.Item> items, long changeId, long version) {
        public AppliedWrite {
            items = List.copyOf(items);
        }
    }

    public record ItineraryWriteScope(long userId, long tripId) {
    }

    private record Replacement(
        ItineraryWriteScope scope,
        List<ItineraryRequests.Item> items,
        String action,
        long version
    ) {
        private Replacement {
            items = List.copyOf(items);
        }
    }
}
