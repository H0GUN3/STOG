package com.stog.backend.social;

import com.stog.backend.plan.EffectiveVisibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeedRepository {
    private final JdbcClient jdbc;

    public FeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedRow> find(Long viewerId, FeedCursor cursor, int limit) {
        return find(viewerId, cursor, limit, false);
    }

    public List<FeedRow> findSaved(long viewerId, FeedCursor cursor, int limit) {
        return find(viewerId, cursor, limit, true);
    }

    private List<FeedRow> find(Long viewerId, FeedCursor cursor, int limit, boolean savedOnly) {
        String viewerLike = viewerId == null
            ? "FALSE"
            : """
                EXISTS (
                    SELECT 1
                    FROM likes viewer_like
                    WHERE viewer_like.user_id = :viewerId
                      AND viewer_like.target_type = 'photo'
                      AND viewer_like.target_id = p.id
                )
                """;
        String viewerSaved = viewerId == null
            ? "FALSE"
            : """
                EXISTS (
                    SELECT 1
                    FROM saved_photos viewer_saved
                    WHERE viewer_saved.user_id = :viewerId
                      AND viewer_saved.photo_id = p.id
                )
                """;
        String savedJoin = savedOnly
            ? "JOIN saved_photos saved_photo ON saved_photo.photo_id = p.id AND saved_photo.user_id = :viewerId"
            : "";
        String sortCreatedAt = savedOnly ? "saved_photo.created_at" : "p.created_at";
        String afterCursor = cursor == null
            ? ""
            : """
                AND (
                    %s < :cursorCreatedAt
                    OR (
                        %s = :cursorCreatedAt
                        AND p.id < :cursorPhotoId
                    )
                )
                """.formatted(sortCreatedAt, sortCreatedAt);
        String sql = """
            SELECT p.id AS photo_id,
                   p.trip_id,
                   p.user_id AS owner_id,
                   u.nickname AS owner_nickname,
                   u.profile_image_key AS owner_profile_image_key,
                   p.thumb_key,
                   p.caption,
                   p.cell_id,
                   p.lat,
                   p.lng,
                   p.taken_at,
                   p.like_count,
                   (SELECT COUNT(*)
                      FROM photo_comments photo_comment
                     WHERE photo_comment.photo_id = p.id) AS comment_count,
                   %s AS liked_by_viewer,
                   %s AS saved_by_viewer,
                   %s AS signed_read_eligible,
                   p.created_at,
                   %s AS cursor_created_at
            FROM photos p
            JOIN trips t ON t.id = p.trip_id
            JOIN users u ON u.id = p.user_id
            %s
            WHERE %s
            %s
            ORDER BY %s DESC, p.id DESC
            LIMIT :limit
            """.formatted(
            viewerLike,
            viewerSaved,
            EffectiveVisibility.SIGNED_READ_PHOTO_ELIGIBILITY_SQL,
            sortCreatedAt,
            savedJoin,
            EffectiveVisibility.FEED_PHOTO_ELIGIBILITY_SQL,
            afterCursor,
            sortCreatedAt
        );
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("limit", limit);
        if (viewerId != null) {
            statement = statement.param("viewerId", viewerId);
        }
        if (cursor != null) {
            statement = statement
                .param("cursorCreatedAt", Timestamp.from(cursor.createdAt()))
                .param("cursorPhotoId", cursor.photoId());
        }
        return statement.query((row, rowNumber) -> new FeedRow(
            row.getLong("photo_id"),
            row.getLong("trip_id"),
            row.getLong("owner_id"),
            row.getString("owner_nickname"),
            row.getString("owner_profile_image_key"),
            row.getString("thumb_key"),
            row.getString("caption"),
            nullableLong(row.getObject("cell_id")),
            row.getObject("lat", Double.class),
            row.getObject("lng", Double.class),
            instant(row.getTimestamp("taken_at")),
            row.getLong("like_count"),
            row.getLong("comment_count"),
            row.getBoolean("liked_by_viewer"),
            row.getBoolean("saved_by_viewer"),
            row.getBoolean("signed_read_eligible"),
            instant(row.getTimestamp("created_at")),
            instant(row.getTimestamp("cursor_created_at"))
        )).list();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record FeedRow(
        long photoId,
        long tripId,
        long ownerId,
        String ownerNickname,
        String ownerProfileImageKey,
        String thumbnailKey,
        String caption,
        Long cellId,
        Double latitude,
        Double longitude,
        Instant takenAt,
        long likeCount,
        long commentCount,
        boolean likedByViewer,
        boolean savedByViewer,
        boolean signedReadEligible,
        Instant createdAt,
        Instant cursorCreatedAt
    ) {
        public FeedRow(
            long photoId,
            long tripId,
            long ownerId,
            String thumbnailKey,
            String caption,
            Long cellId,
            Double latitude,
            Double longitude,
            Instant takenAt,
            long likeCount,
            boolean likedByViewer,
            boolean signedReadEligible,
            Instant createdAt
        ) {
            this(
                photoId, tripId, ownerId, null, null, thumbnailKey, caption, cellId, latitude,
                longitude, takenAt, likeCount, 0, likedByViewer, false, signedReadEligible,
                createdAt, createdAt
            );
        }
    }
}
