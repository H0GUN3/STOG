package com.stog.backend.social;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SavedPhotoRepository {
    private final JdbcClient jdbc;

    public SavedPhotoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long userId, long photoId) {
        return jdbc.sql("""
                INSERT INTO saved_photos (user_id, photo_id)
                VALUES (:userId, :photoId)
                ON CONFLICT (user_id, photo_id) DO NOTHING
                """)
            .params(Map.of("userId", userId, "photoId", photoId))
            .update() == 1;
    }

    public boolean delete(long userId, long photoId) {
        return jdbc.sql("""
                DELETE FROM saved_photos
                WHERE user_id = :userId
                  AND photo_id = :photoId
                """)
            .params(Map.of("userId", userId, "photoId", photoId))
            .update() == 1;
    }
}
