package com.stog.backend.social;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoCommentRepository {
    private final JdbcClient jdbc;

    public PhotoCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CommentRow> findByPhoto(long photoId) {
        return jdbc.sql("""
                SELECT c.id,
                       c.photo_id,
                       c.user_id,
                       u.nickname AS author_name,
                       c.body,
                       c.created_at
                FROM photo_comments c
                JOIN users u ON u.id = c.user_id
                WHERE c.photo_id = :photoId
                ORDER BY c.created_at ASC, c.id ASC
                """)
            .param("photoId", photoId)
            .query((row, rowNumber) -> new CommentRow(
                row.getLong("id"),
                row.getLong("photo_id"),
                row.getLong("user_id"),
                row.getString("author_name"),
                row.getString("body"),
                instant(row.getTimestamp("created_at"))
            ))
            .list();
    }

    public CommentRow insert(long userId, long photoId, String body) {
        Long id = jdbc.sql("""
                INSERT INTO photo_comments (photo_id, user_id, body)
                VALUES (:photoId, :userId, :body)
                RETURNING id
                """)
            .params(Map.of("photoId", photoId, "userId", userId, "body", body))
            .query(Long.class)
            .single();
        return findById(id).orElseThrow();
    }

    private Optional<CommentRow> findById(long id) {
        return jdbc.sql("""
                SELECT c.id,
                       c.photo_id,
                       c.user_id,
                       u.nickname AS author_name,
                       c.body,
                       c.created_at
                FROM photo_comments c
                JOIN users u ON u.id = c.user_id
                WHERE c.id = :id
                """)
            .param("id", id)
            .query((row, rowNumber) -> new CommentRow(
                row.getLong("id"),
                row.getLong("photo_id"),
                row.getLong("user_id"),
                row.getString("author_name"),
                row.getString("body"),
                instant(row.getTimestamp("created_at"))
            ))
            .optional();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CommentRow(
        long id,
        long photoId,
        long userId,
        String authorName,
        String body,
        Instant createdAt
    ) {
    }
}
