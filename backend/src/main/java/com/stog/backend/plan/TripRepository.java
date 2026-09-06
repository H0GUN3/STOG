package com.stog.backend.plan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripRepository {
    private final JdbcClient jdbc;

    public TripRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TripRecord create(long ownerId, TripRequests.Create request) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("ownerId", ownerId);
        parameters.put("title", request.title());
        parameters.put("activityType", request.activity_type());
        parameters.put("startDate", request.planned_start_date());
        parameters.put("endDate", request.planned_end_date());
        parameters.put(
            "regionCode",
            request.region_code() == null ? "JEONBUK" : request.region_code()
        );
        TripRecord created = jdbc.sql(
                """
                INSERT INTO trips (
                    owner_id,
                    title,
                    activity_type,
                    planned_start_date,
                    planned_end_date,
                    region_code
                )
                VALUES (
                    :ownerId,
                    :title,
                    :activityType,
                    :startDate,
                    :endDate,
                    :regionCode
                )
                RETURNING id, title, activity_type, mode, visibility,
                    planned_start_date, planned_end_date, cover_image_key, region_code
                """
            )
            .params(parameters)
            .query((row, rowNumber) -> new TripRecord(
                row.getLong("id"),
                row.getString("title"),
                row.getString("activity_type"),
                row.getString("mode"),
                row.getString("visibility"),
                row.getObject("planned_start_date", java.time.LocalDate.class),
                row.getObject("planned_end_date", java.time.LocalDate.class),
                row.getString("cover_image_key"),
                row.getString("region_code")
            ))
            .single();
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :ownerId)")
            .param("tripId", created.id())
            .param("ownerId", ownerId)
            .update();
        jdbc.sql(
                """
                INSERT INTO trip_member_collection_states (trip_id, user_id)
                VALUES (:tripId, :ownerId)
                """
            )
            .param("tripId", created.id())
            .param("ownerId", ownerId)
            .update();
        jdbc.sql("INSERT INTO trip_itinerary_states (trip_id) VALUES (:tripId)")
            .param("tripId", created.id())
            .update();
        return created;
    }

    public Optional<TripRecord> update(
        long ownerId,
        long tripId,
        TripRequests.Update request
    ) {
        return jdbc.sql(
                """
                UPDATE trips
                SET title = :title,
                    planned_start_date = :startDate,
                    planned_end_date = :endDate
                WHERE id = :tripId
                  AND owner_id = :ownerId
                RETURNING id, title, activity_type, mode, visibility,
                          planned_start_date, planned_end_date, cover_image_key, region_code
                """
            )
            .param("ownerId", ownerId)
            .param("tripId", tripId)
            .param("title", request.title())
            .param("startDate", request.planned_start_date())
            .param("endDate", request.planned_end_date())
            .query((row, rowNumber) -> new TripRecord(
                row.getLong("id"),
                row.getString("title"),
                row.getString("activity_type"),
                row.getString("mode"),
                row.getString("visibility"),
                row.getObject("planned_start_date", java.time.LocalDate.class),
                row.getObject("planned_end_date", java.time.LocalDate.class),
                row.getString("cover_image_key"),
                row.getString("region_code")
            ))
            .optional();
    }

    public Optional<TripRecord> findForMember(long userId, long tripId) {
        return jdbc.sql(
                """
                SELECT id, title, activity_type, mode, visibility,
                       planned_start_date, planned_end_date, cover_image_key, region_code
                FROM trips
                WHERE id = :tripId
                  AND (
                      owner_id = :userId
                      OR EXISTS (
                          SELECT 1
                          FROM trip_members member
                          WHERE member.trip_id = trips.id
                            AND member.user_id = :userId
                            AND member.left_at IS NULL
                      )
                  )
                """
            )
            .param("userId", userId)
            .param("tripId", tripId)
            .query((row, rowNumber) -> new TripRecord(
                row.getLong("id"),
                row.getString("title"),
                row.getString("activity_type"),
                row.getString("mode"),
                row.getString("visibility"),
                row.getObject("planned_start_date", java.time.LocalDate.class),
                row.getObject("planned_end_date", java.time.LocalDate.class),
                row.getString("cover_image_key"),
                row.getString("region_code")
            ))
            .optional();
    }

    public List<TripRecord> findMine(long ownerId) {
        return jdbc.sql(
                """
                SELECT id, title, activity_type, mode, visibility,
                       planned_start_date, planned_end_date, cover_image_key, region_code
                FROM trips
                WHERE owner_id = :ownerId
                   OR EXISTS (
                       SELECT 1
                       FROM trip_members member
                       WHERE member.trip_id = trips.id
                         AND member.user_id = :ownerId
                         AND member.left_at IS NULL
                   )
                ORDER BY created_at DESC, id DESC
                """
            )
            .param("ownerId", ownerId)
            .query((row, rowNumber) -> new TripRecord(
                row.getLong("id"),
                row.getString("title"),
                row.getString("activity_type"),
                row.getString("mode"),
                row.getString("visibility"),
                row.getObject("planned_start_date", java.time.LocalDate.class),
                row.getObject("planned_end_date", java.time.LocalDate.class),
                row.getString("cover_image_key"),
                row.getString("region_code")
            ))
            .list();
    }

    public HomeData findHome(long userId) {
        HomeMetrics metrics = jdbc.sql(
                """
                SELECT
                    (
                        SELECT COUNT(*)
                        FROM trips trip
                        WHERE (
                            trip.owner_id = :userId
                            OR EXISTS (
                                SELECT 1
                                FROM trip_members member
                                WHERE member.trip_id = trip.id
                                  AND member.user_id = :userId
                                  AND member.left_at IS NULL
                            )
                        )
                          AND trip.mode IN ('active', 'dormant')
                          AND trip.planned_start_date >= date_trunc('month', CURRENT_DATE)::date
                          AND trip.planned_start_date
                              < (date_trunc('month', CURRENT_DATE) + INTERVAL '1 month')::date
                    ) AS monthly_trip_count,
                    (
                        SELECT COUNT(*)
                        FROM (
                            SELECT visit.cell_id
                            FROM visits visit
                            WHERE visit.user_id = :userId
                              AND visit.status = 'visited'
                              AND NOT visit.is_interpolated
                            UNION
                            SELECT photo.cell_id
                            FROM photos photo
                            WHERE photo.user_id = :userId
                              AND photo.cell_id IS NOT NULL
                              AND photo.lat IS NOT NULL
                              AND photo.lng IS NOT NULL
                              AND photo.moderation_status <> 'blocked'
                        ) visited_cells
                    ) AS visited_cell_count,
                    (
                        SELECT COUNT(*)
                        FROM basket_items item
                        JOIN trips trip ON trip.id = item.trip_id
                        WHERE (
                            trip.owner_id = :userId
                            OR EXISTS (
                                SELECT 1
                                FROM trip_members member
                                WHERE member.trip_id = trip.id
                                  AND member.user_id = :userId
                                  AND member.left_at IS NULL
                            )
                        )
                    ) AS saved_place_count,
                    (
                        SELECT COALESCE(SUM(photo.like_count), 0)
                        FROM photos photo
                        WHERE photo.user_id = :userId
                          AND photo.created_at >= date_trunc('month', CURRENT_DATE)
                          AND photo.created_at
                              < date_trunc('month', CURRENT_DATE) + INTERVAL '1 month'
                    ) AS monthly_received_like_count
                """
            )
            .param("userId", userId)
            .query((row, rowNumber) -> new HomeMetrics(
                row.getLong("monthly_trip_count"),
                row.getLong("visited_cell_count"),
                row.getLong("saved_place_count"),
                row.getLong("monthly_received_like_count")
            ))
            .single();
        return new HomeData(
            metrics.monthlyTripCount(),
            metrics.visitedCellCount(),
            metrics.savedPlaceCount(),
            metrics.monthlyReceivedLikeCount(),
            findMine(userId).stream()
                .filter(trip -> "active".equals(trip.mode()) || "dormant".equals(trip.mode()))
                .toList()
        );
    }

    public void setCoverImageKey(long ownerId, long tripId, String objectKey) {
        int updated = jdbc.sql(
                """
                UPDATE trips
                SET cover_image_key = :objectKey
                WHERE id = :tripId AND owner_id = :ownerId
                """
            )
            .param("ownerId", ownerId)
            .param("tripId", tripId)
            .param("objectKey", objectKey)
            .update();
        if (updated != 1) {
            throw new IllegalArgumentException("Trip cover target is invalid");
        }
    }

    public Optional<VisitLifecycle> lockVisitLifecycle(long tripId) {
        return jdbc.sql(
                """
                SELECT mode, ended_at
                FROM trips
                WHERE id = :tripId
                FOR SHARE
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new VisitLifecycle(
                row.getString("mode"),
                row.getObject("ended_at", OffsetDateTime.class) == null
                    ? null
                    : row.getObject("ended_at", OffsetDateTime.class).toInstant()
            ))
            .optional();
    }

    public Optional<LockedLifecycle> lockLifecycle(long tripId) {
        return jdbc.sql(
                """
                SELECT id, owner_id, mode, started_at, ended_at
                FROM trips
                WHERE id = :tripId
                FOR UPDATE
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new LockedLifecycle(
                row.getLong("id"),
                row.getLong("owner_id"),
                row.getString("mode"),
                instant(row.getObject("started_at", OffsetDateTime.class)),
                instant(row.getObject("ended_at", OffsetDateTime.class))
            ))
            .optional();
    }

    public void activateIfDormant(long tripId, Instant now) {
        jdbc.sql(
                """
                UPDATE trips
                SET mode = 'active', started_at = COALESCE(started_at, :now)
                WHERE id = :tripId AND mode = 'dormant'
                """
            )
            .param("tripId", tripId)
            .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
            .update();
    }

    public TripResponses.Mode updateMode(LockedLifecycle trip, String mode, Instant now) {
        Instant startedAt = trip.startedAt();
        Instant endedAt = trip.endedAt();
        if ("active".equals(mode) && startedAt == null) {
            startedAt = now;
        }
        if ("ended".equals(mode)) {
            endedAt = now;
        }
        return jdbc.sql(
                """
                UPDATE trips
                SET mode = :mode,
                    started_at = :startedAt,
                    ended_at = :endedAt
                WHERE id = :tripId
                RETURNING id, mode, started_at, ended_at
                """
            )
            .param("mode", mode)
            .param("startedAt", startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC))
            .param("endedAt", endedAt == null ? null : OffsetDateTime.ofInstant(endedAt, java.time.ZoneOffset.UTC))
            .param("tripId", trip.id())
            .query((row, rowNumber) -> new TripResponses.Mode(
                row.getLong("id"),
                row.getString("mode"),
                instant(row.getObject("started_at", OffsetDateTime.class)),
                instant(row.getObject("ended_at", OffsetDateTime.class))
            ))
            .single();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public Optional<TripResponses.Archive> findArchive(long tripId) {
        List<TripResponses.ArchiveTrailPoint> trail = jdbc.sql(
                """
                SELECT id, user_id, cell_id, lat, lng, entered_at, left_at,
                       status, is_interpolated
                FROM visits
                WHERE trip_id = :tripId
                ORDER BY entered_at, id
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new TripResponses.ArchiveTrailPoint(
                row.getLong("id"),
                row.getLong("user_id"),
                com.stog.backend.cell.CellIdCalculator.toWire(row.getLong("cell_id")),
                row.getDouble("lat"),
                row.getDouble("lng"),
                row.getTimestamp("entered_at").toInstant(),
                row.getTimestamp("left_at").toInstant(),
                row.getString("status"),
                row.getBoolean("is_interpolated")
            ))
            .list();
        return jdbc.sql(
                """
                SELECT trip.id, trip.mode, trip.planned_start_date, trip.planned_end_date,
                       trip.started_at, trip.ended_at,
                       (SELECT COUNT(*) FROM itinerary_items item
                        WHERE item.trip_id = trip.id) AS itinerary_item_count,
                       (SELECT COUNT(*) FROM itinerary_changes history
                        WHERE history.trip_id = trip.id) AS itinerary_change_count,
                       (SELECT COUNT(*) FROM visits visit
                        WHERE visit.trip_id = trip.id) AS visit_count,
                       (SELECT COUNT(*) FROM visits visit
                        WHERE visit.trip_id = trip.id AND visit.status = 'visited') AS visited_count,
                       (SELECT COUNT(*) FROM visits visit
                        WHERE visit.trip_id = trip.id AND visit.status = 'passed') AS passed_count,
                       (SELECT COUNT(DISTINCT visit.cell_id) FROM visits visit
                        WHERE visit.trip_id = trip.id AND visit.status = 'visited') AS visited_cell_count,
                       (SELECT COUNT(*) FROM photos photo
                        WHERE photo.trip_id = trip.id) AS photo_count
                FROM trips trip
                WHERE trip.id = :tripId
                """
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> new TripResponses.Archive(
                row.getLong("id"),
                row.getString("mode"),
                row.getObject("planned_start_date", java.time.LocalDate.class),
                row.getObject("planned_end_date", java.time.LocalDate.class),
                instant(row.getObject("started_at", OffsetDateTime.class)),
                instant(row.getObject("ended_at", OffsetDateTime.class)),
                row.getLong("itinerary_item_count"),
                row.getLong("itinerary_change_count"),
                row.getLong("visit_count"),
                row.getLong("visited_count"),
                row.getLong("passed_count"),
                row.getLong("visited_cell_count"),
                row.getLong("photo_count"),
                List.copyOf(trail)
            ))
            .optional();
    }

    public Optional<Integer> dayCount(long tripId) {
        return jdbc.sql(
                """
                SELECT (planned_end_date - planned_start_date + 1)::integer
                FROM trips
                WHERE id = :tripId
                  AND planned_start_date IS NOT NULL
                  AND planned_end_date IS NOT NULL
                """
            )
            .param("tripId", tripId)
            .query(Integer.class)
            .optional();
    }

    private record HomeMetrics(
        long monthlyTripCount,
        long visitedCellCount,
        long savedPlaceCount,
        long monthlyReceivedLikeCount
    ) {
    }

    public record TripRecord(
        long id,
        String title,
        String activityType,
        String mode,
        String visibility,
        java.time.LocalDate plannedStartDate,
        java.time.LocalDate plannedEndDate,
        String coverImageKey,
        String regionCode
    ) {
    }

    public record HomeData(
        long monthlyTripCount,
        long visitedCellCount,
        long savedPlaceCount,
        long monthlyReceivedLikeCount,
        List<TripRecord> trips
    ) {
    }

    public record VisitLifecycle(String mode, Instant endedAt) {
    }

    public record LockedLifecycle(
        long id,
        long ownerId,
        String mode,
        Instant startedAt,
        Instant endedAt
    ) {
    }
}
