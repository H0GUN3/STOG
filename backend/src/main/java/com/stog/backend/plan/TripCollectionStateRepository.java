package com.stog.backend.plan;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripCollectionStateRepository {
    private static final RowMapper<TripCollectionResponses.State> STATE_MAPPER =
        (row, rowNumber) -> new TripCollectionResponses.State(
            row.getLong("trip_id"),
            row.getLong("user_id"),
            row.getString("collector_state"),
            row.getString("permission_state"),
            row.getString("sync_cursor"),
            row.getLong("mode_version"),
            instant(row.getObject("collector_started_at", OffsetDateTime.class)),
            instant(row.getObject("updated_at", OffsetDateTime.class))
        );

    private final JdbcClient jdbc;

    public TripCollectionStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TripCollectionResponses.State> find(long tripId, long userId) {
        return jdbc.sql("""
                SELECT trip_id, user_id, collector_state, permission_state,
                       sync_cursor, mode_version, collector_started_at, updated_at
                FROM trip_member_collection_states
                WHERE trip_id = :tripId AND user_id = :userId
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .query(STATE_MAPPER)
            .optional();
    }

    public Optional<TripCollectionResponses.State> update(
        long tripId,
        long userId,
        TripCollectionRequests.Update request
    ) {
        return jdbc.sql("""
                UPDATE trip_member_collection_states
                SET collector_state = :collectorState,
                    permission_state = :permissionState,
                    sync_cursor = :syncCursor,
                    mode_version = mode_version + 1,
                    collector_started_at = CASE
                        WHEN :collectorState = 'active'
                            THEN COALESCE(collector_started_at, CURRENT_TIMESTAMP)
                        ELSE collector_started_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE trip_id = :tripId
                  AND user_id = :userId
                  AND mode_version = :expectedModeVersion
                RETURNING trip_id, user_id, collector_state, permission_state,
                          sync_cursor, mode_version, collector_started_at, updated_at
                """)
            .param("collectorState", request.collector_state())
            .param("permissionState", request.permission_state())
            .param("syncCursor", request.sync_cursor(), java.sql.Types.VARCHAR)
            .param("tripId", tripId)
            .param("userId", userId)
            .param("expectedModeVersion", request.expected_mode_version())
            .query(STATE_MAPPER)
            .optional();
    }

    public long activeCount(long tripId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM trip_member_collection_states state
                JOIN trip_members member
                  ON member.trip_id = state.trip_id AND member.user_id = state.user_id
                WHERE state.trip_id = :tripId
                  AND state.collector_state = 'active'
                  AND member.left_at IS NULL
                """)
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }

    public void endAll(long tripId) {
        jdbc.sql("""
                UPDATE trip_member_collection_states
                SET collector_state = 'ended',
                    mode_version = mode_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE trip_id = :tripId AND collector_state <> 'ended'
                """)
            .param("tripId", tripId)
            .update();
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
