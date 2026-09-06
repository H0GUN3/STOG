package com.stog.backend.plan;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EffectiveVisibilityRepository {
    private final JdbcClient jdbc;

    public EffectiveVisibilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TripView> findTrip(long tripId) {
        return jdbc.sql("""
                SELECT owner_id, visibility
                FROM trips
                WHERE id = :tripId
                """)
            .param("tripId", tripId)
            .query((row, rowNumber) -> new TripView(
                row.getLong("owner_id"),
                row.getString("visibility")
            ))
            .optional();
    }

    public Optional<PhotoView> findPhoto(long photoId) {
        return jdbc.sql("""
                SELECT p.user_id AS photo_owner_id,
                       p.trip_id,
                       t.owner_id AS trip_owner_id,
                       t.visibility AS trip_visibility,
                       p.visibility AS photo_visibility,
                       %s AS publicly_eligible
                FROM photos p
                LEFT JOIN trips t ON t.id = p.trip_id
                WHERE p.id = :photoId
                """.formatted(EffectiveVisibility.SIGNED_READ_PHOTO_ELIGIBILITY_SQL))
            .param("photoId", photoId)
            .query((row, rowNumber) -> new PhotoView(
                row.getLong("photo_owner_id"),
                (Long) row.getObject("trip_id"),
                (Long) row.getObject("trip_owner_id"),
                row.getString("trip_visibility"),
                row.getString("photo_visibility"),
                row.getBoolean("publicly_eligible")
            ))
            .optional();
    }

    public boolean isActiveMember(long userId, long tripId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM trip_members
                    WHERE trip_id = :tripId
                      AND user_id = :userId
                      AND left_at IS NULL
                )
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public record TripView(long ownerId, String visibility) {
    }

    public record PhotoView(
        long photoOwnerId,
        Long tripId,
        Long tripOwnerId,
        String tripVisibility,
        String photoVisibility,
        boolean publiclyEligible
    ) {
    }
}
