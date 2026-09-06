package com.stog.backend.social;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoLikeRepository {
    private final JdbcClient jdbc;

    public PhotoLikeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long userId, long photoId) {
        return jdbc.sql("""
                INSERT INTO likes (user_id, target_type, target_id)
                VALUES (:userId, 'photo', :photoId)
                ON CONFLICT (user_id, target_type, target_id) DO NOTHING
                """)
            .params(Map.of("userId", userId, "photoId", photoId))
            .update() == 1;
    }

    public boolean delete(long userId, long photoId) {
        return jdbc.sql("""
                DELETE FROM likes
                WHERE user_id = :userId
                  AND target_type = 'photo'
                  AND target_id = :photoId
                """)
            .params(Map.of("userId", userId, "photoId", photoId))
            .update() == 1;
    }
}
