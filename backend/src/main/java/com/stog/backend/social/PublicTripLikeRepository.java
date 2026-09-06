package com.stog.backend.social;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PublicTripLikeRepository {
    private final JdbcClient jdbc;

    public PublicTripLikeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(long userId, long tripId) {
        return jdbc.sql("""
                INSERT INTO likes (user_id, target_type, target_id)
                VALUES (:userId, 'trip', :tripId)
                ON CONFLICT (user_id, target_type, target_id) DO NOTHING
                """)
            .param("userId", userId).param("tripId", tripId).update() == 1;
    }

    public boolean delete(long userId, long tripId) {
        return jdbc.sql("""
                DELETE FROM likes WHERE user_id = :userId
                  AND target_type = 'trip' AND target_id = :tripId
                """)
            .param("userId", userId).param("tripId", tripId).update() == 1;
    }

    public long count(long tripId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM likes
                WHERE target_type = 'trip' AND target_id = :tripId
                """)
            .param("tripId", tripId).query(Long.class).single();
    }
}
