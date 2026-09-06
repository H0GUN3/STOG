package com.stog.backend.plan;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TripMembershipRepository {
    private final JdbcClient jdbc;

    public TripMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean userExists(long userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id = :userId)")
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public boolean isOwner(long userId, long tripId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM trips
                    WHERE id = :tripId
                      AND owner_id = :userId
                )
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public boolean isActiveMember(long userId, long tripId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM trips t
                    WHERE t.id = :tripId
                      AND (
                          t.owner_id = :userId
                          OR EXISTS (
                              SELECT 1
                              FROM trip_members m
                              WHERE m.trip_id = t.id
                                AND m.user_id = :userId
                                AND m.left_at IS NULL
                          )
                      )
                )
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public boolean isVisitMember(
        long userId,
        long tripId,
        Instant enteredAt,
        Instant leftAt
    ) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM trips trip
                    WHERE trip.id = :tripId
                      AND (
                          trip.owner_id = :userId
                          OR EXISTS (
                              SELECT 1
                              FROM trip_members member
                              WHERE member.trip_id = trip.id
                                AND member.user_id = :userId
                                AND (
                                    member.left_at IS NULL
                                    OR (
                                        member.joined_at <= :enteredAt
                                        AND member.left_at >= :leftAt
                                    )
                                )
                          )
                      )
                )
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .param("enteredAt", OffsetDateTime.ofInstant(enteredAt, java.time.ZoneOffset.UTC))
            .param("leftAt", OffsetDateTime.ofInstant(leftAt, java.time.ZoneOffset.UTC))
            .query(Boolean.class)
            .single();
    }

    public Optional<MembersView> findActiveMembers(long tripId) {
        Optional<Long> ownerId = jdbc.sql("SELECT owner_id FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .optional();
        if (ownerId.isEmpty()) {
            return Optional.empty();
        }
        List<TripMembershipResponses.Member> members = jdbc.sql("""
                SELECT member.user_id, users.nickname, member.joined_at
                FROM trip_members member
                JOIN users ON users.id = member.user_id
                WHERE member.trip_id = :tripId
                  AND member.left_at IS NULL
                UNION ALL
                SELECT trip.owner_id, users.nickname, trip.created_at
                FROM trips trip
                JOIN users ON users.id = trip.owner_id
                WHERE trip.id = :tripId
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trip_members owner_member
                      WHERE owner_member.trip_id = trip.id
                        AND owner_member.user_id = trip.owner_id
                        AND owner_member.left_at IS NULL
                  )
                ORDER BY user_id
                """)
            .param("tripId", tripId)
            .query((row, rowNumber) -> new TripMembershipResponses.Member(
                row.getLong("user_id"),
                row.getString("nickname"),
                row.getObject("joined_at", OffsetDateTime.class).toInstant()
            ))
            .list();
        return Optional.of(new MembersView(ownerId.get(), members));
    }

    public Optional<LockedTrip> lockTrip(long tripId) {
        return jdbc.sql("""
                SELECT id, owner_id
                FROM trips
                WHERE id = :tripId
                FOR UPDATE
                """)
            .param("tripId", tripId)
            .query((row, rowNumber) -> new LockedTrip(
                row.getLong("id"),
                row.getLong("owner_id")
            ))
            .optional();
    }

    public void markAsGroup(long tripId) {
        jdbc.sql("""
                UPDATE trips
                SET is_group = TRUE
                WHERE id = :tripId
                """)
            .param("tripId", tripId)
            .update();
    }

    public void createInvite(long tripId, long createdBy, String tokenHash) {
        jdbc.sql("""
                INSERT INTO trip_invites (trip_id, token_hash, created_by)
                VALUES (:tripId, :tokenHash, :createdBy)
                """)
            .param("tripId", tripId)
            .param("tokenHash", tokenHash)
            .param("createdBy", createdBy)
            .update();
    }

    public Optional<LockedInvite> lockInvite(String tokenHash) {
        return jdbc.sql("""
                SELECT i.trip_id, t.owner_id
                FROM trip_invites i
                JOIN trips t ON t.id = i.trip_id
                WHERE i.token_hash = :tokenHash
                FOR UPDATE
                """)
            .param("tokenHash", tokenHash)
            .query((row, rowNumber) -> new LockedInvite(
                row.getLong("trip_id"),
                row.getLong("owner_id")
            ))
            .optional();
    }

    public void activateMember(long tripId, long userId) {
        jdbc.sql("""
                INSERT INTO trip_members (trip_id, user_id)
                VALUES (:tripId, :userId)
                ON CONFLICT (trip_id, user_id)
                DO UPDATE SET left_at = NULL
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
        jdbc.sql("""
                INSERT INTO trip_member_collection_states (trip_id, user_id)
                VALUES (:tripId, :userId)
                ON CONFLICT (trip_id, user_id) DO NOTHING
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
    }

    public boolean deactivateMember(long tripId, long userId) {
        boolean deactivated = jdbc.sql("""
                UPDATE trip_members
                SET left_at = CURRENT_TIMESTAMP
                WHERE trip_id = :tripId
                  AND user_id = :userId
                  AND left_at IS NULL
                """)
            .param("tripId", tripId)
            .param("userId", userId)
            .update() == 1;
        if (deactivated) {
            jdbc.sql("""
                    UPDATE trip_member_collection_states
                    SET collector_state = 'ended',
                        mode_version = mode_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE trip_id = :tripId AND user_id = :userId
                    """)
                .param("tripId", tripId)
                .param("userId", userId)
                .update();
        }
        return deactivated;
    }

    public long activeNonOwnerCount(long tripId, long ownerId) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM trip_members
                WHERE trip_id = :tripId
                  AND user_id <> :ownerId
                  AND left_at IS NULL
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
    }

    public void deleteTrip(long tripId) {
        jdbc.sql("DELETE FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .update();
    }

    public record MembersView(
        long ownerId,
        List<TripMembershipResponses.Member> members
    ) {
    }

    public record LockedTrip(long id, long ownerId) {
    }

    public record LockedInvite(long tripId, long ownerId) {
    }
}
