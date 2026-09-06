package com.stog.backend.profile;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileSummaryRepository {
    private final JdbcClient jdbc;

    public ProfileSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Metrics metrics(long userId) {
        return jdbc.sql(
                """
                SELECT
                    (
                        SELECT COUNT(*)
                        FROM trips trip
                        WHERE trip.owner_id = :userId
                           OR EXISTS (
                               SELECT 1
                               FROM trip_members member
                               WHERE member.trip_id = trip.id
                                 AND member.user_id = :userId
                                 AND member.left_at IS NULL
                           )
                    ) AS trip_count,
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
                        FROM photos photo
                        WHERE photo.user_id = :userId
                    ) AS photo_count
                """
            )
            .param("userId", userId)
            .query((row, rowNumber) -> new Metrics(
                row.getLong("trip_count"),
                row.getLong("visited_cell_count"),
                row.getLong("photo_count")
            ))
            .single();
    }

    public record Metrics(long tripCount, long visitedCellCount, long photoCount) {
    }
}
