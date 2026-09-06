package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class VisitServiceTest {
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;
    private static final long CELL_ID = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
    private static final String CELL_ID_WIRE = CellIdCalculator.toWire(CELL_ID);
    private static final Instant ENTERED = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LEFT = ENTERED.plus(Duration.ofMinutes(30));

    @Autowired
    private VisitService visits;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void removeFixtures() {
        jdbc.sql(
                """
                DELETE FROM trips
                WHERE owner_id IN (
                    SELECT id FROM users WHERE nickname LIKE 'todo4-visit-%'
                )
                """
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'todo4-visit-%'").update();
    }

    @Test
    void recordsANewMemberOwnedVisit() {
        long ownerId = user("owner");
        long tripId = trip(ownerId);
        VisitRequests.Record request = request(UUID.randomUUID(), ENTERED, LEFT);

        VisitResponses.Recorded recorded = visits.record(ownerId, tripId, request);

        assertThat(recorded.client_visit_id()).isEqualTo(request.client_visit_id());
        assertThat(recorded.payload_fingerprint()).isEqualTo(request.payload_fingerprint());
        assertThat(recorded.cell_id()).isEqualTo(CELL_ID_WIRE);
        assertThat(recorded.status()).isEqualTo("visited");
        assertThat(jdbc.sql("SELECT user_id FROM visits WHERE id = :id")
            .param("id", recorded.id())
            .query(Long.class)
            .single()).isEqualTo(ownerId);
    }

    @Test
    void identicalReplayReturnsTheOriginalResponse() {
        long ownerId = user("replay-owner");
        long tripId = trip(ownerId);
        VisitRequests.Record request = request(UUID.randomUUID(), ENTERED, LEFT);

        VisitResponses.Recorded first = visits.record(ownerId, tripId, request);
        VisitResponses.Recorded replay = visits.record(ownerId, tripId, request);

        assertThat(replay).isEqualTo(first);
        assertThat(visitCount(tripId)).isEqualTo(1);
    }

    @Test
    void changedPayloadForTheSameMemberAndKeyReturnsConflict() {
        long ownerId = user("conflict-owner");
        long tripId = trip(ownerId);
        UUID clientVisitId = UUID.randomUUID();
        visits.record(ownerId, tripId, request(clientVisitId, ENTERED, LEFT));
        VisitRequests.Record changed = request(
            clientVisitId,
            ENTERED,
            LEFT.plus(Duration.ofMinutes(1))
        );

        assertStatus(HttpStatus.CONFLICT, () -> visits.record(ownerId, tripId, changed));
        assertThat(visitCount(tripId)).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalReplayCreatesOneRowAndReturnsOneResponse() throws Exception {
        long ownerId = user("concurrent-owner");
        long tripId = trip(ownerId);
        VisitRequests.Record request = request(UUID.randomUUID(), ENTERED, LEFT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent visit start was not released");
                }
                return visits.record(ownerId, tripId, request);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent visit start was not released");
                }
                return visits.record(ownerId, tripId, request);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            VisitResponses.Recorded firstResponse = first.get(5, TimeUnit.SECONDS);
            VisitResponses.Recorded secondResponse = second.get(5, TimeUnit.SECONDS);
            assertThat(secondResponse).isEqualTo(firstResponse);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(visitCount(tripId)).isEqualTo(1);
    }

    @Test
    void nonMemberCannotRecordAVisit() {
        long ownerId = user("denial-owner");
        long nonMemberId = user("denial-nonmember");
        long tripId = trip(ownerId);

        assertStatus(
            HttpStatus.FORBIDDEN,
            () -> visits.record(nonMemberId, tripId, request(UUID.randomUUID(), ENTERED, LEFT))
        );
        assertThat(visitCount(tripId)).isZero();
    }

    @Test
    void sameKeyCellAndIntervalRemainIsolatedPerMember() {
        long ownerId = user("isolation-owner");
        long memberId = user("isolation-member");
        long tripId = trip(ownerId);
        member(tripId, memberId);
        UUID clientVisitId = UUID.randomUUID();
        VisitRequests.Record request = request(clientVisitId, ENTERED, LEFT);

        VisitResponses.Recorded ownerVisit = visits.record(ownerId, tripId, request);
        VisitResponses.Recorded memberVisit = visits.record(memberId, tripId, request);

        assertThat(memberVisit.id()).isNotEqualTo(ownerVisit.id());
        assertThat(visitCount(tripId)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT user_id) FROM visits WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isEqualTo(2);
    }

    @Test
    void endedTripRejectsAVisitObservedAfterEnd() {
        long ownerId = user("ended-owner");
        long tripId = trip(ownerId);
        Instant endedAt = Instant.now()
            .minus(Duration.ofHours(1))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        endTrip(tripId, endedAt);

        assertStatus(
            HttpStatus.CONFLICT,
            () -> visits.record(
                ownerId,
                tripId,
                request(UUID.randomUUID(), endedAt.plusSeconds(1), endedAt.plusSeconds(61))
            )
        );
        assertThat(visitCount(tripId)).isZero();
    }

    @Test
    void endedTripAcceptsAQualifyingLateEventWithinGrace() {
        long ownerId = user("late-owner");
        long tripId = trip(ownerId);
        Instant endedAt = Instant.now()
            .minus(Duration.ofHours(1))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        endTrip(tripId, endedAt);
        VisitRequests.Record late = request(
            UUID.randomUUID(),
            endedAt.minus(Duration.ofMinutes(30)),
            endedAt
        );

        assertThat(visits.record(ownerId, tripId, late).client_visit_id())
            .isEqualTo(late.client_visit_id());
        assertThat(visitCount(tripId)).isEqualTo(1);
    }

    @Test
    void endedTripRejectsAnExpiredLateEvent() {
        long ownerId = user("expired-owner");
        long tripId = trip(ownerId);
        Instant endedAt = Instant.now()
            .minus(Duration.ofHours(25))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        endTrip(tripId, endedAt);

        assertStatus(
            HttpStatus.CONFLICT,
            () -> visits.record(
                ownerId,
                tripId,
                request(
                    UUID.randomUUID(),
                    endedAt.minus(Duration.ofMinutes(30)),
                    endedAt
                )
            )
        );
        assertThat(visitCount(tripId)).isZero();
    }

    @Test
    void committedReplayRemainsStableAfterTripEnds() {
        long ownerId = user("ended-replay-owner");
        long tripId = trip(ownerId);
        VisitRequests.Record request = request(UUID.randomUUID(), ENTERED, LEFT);
        VisitResponses.Recorded first = visits.record(ownerId, tripId, request);
        endTrip(tripId, Instant.now());

        assertThat(visits.record(ownerId, tripId, request)).isEqualTo(first);
        assertThat(visitCount(tripId)).isEqualTo(1);
    }

    private VisitRequests.Record request(UUID clientVisitId, Instant enteredAt, Instant leftAt) {
        String status = Duration.between(enteredAt, leftAt).compareTo(Duration.ofMinutes(30)) >= 0
            ? "visited"
            : "passed";
        String fingerprint = fingerprint(
            CELL_ID_WIRE,
            LATITUDE,
            LONGITUDE,
            enteredAt,
            leftAt,
            status,
            false
        );
        return new VisitRequests.Record(
            clientVisitId,
            fingerprint,
            CELL_ID_WIRE,
            LATITUDE,
            LONGITUDE,
            enteredAt,
            leftAt,
            status,
            false
        );
    }

    static String fingerprint(
        String cellId,
        double latitude,
        double longitude,
        Instant enteredAt,
        Instant leftAt,
        String status,
        boolean interpolated
    ) {
        String canonical = "cell_id=" + cellId.toLowerCase()
            + "\nlat=" + decimal(latitude)
            + "\nlng=" + decimal(longitude)
            + "\nentered_at=" + enteredAt
            + "\nleft_at=" + leftAt
            + "\nstatus=" + status
            + "\nis_interpolated=" + interpolated;
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(expected);
    }

    private long user(String suffix) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "todo4-visit-" + suffix + "-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId) {
        return jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type, mode)
                VALUES (:ownerId, 'todo4 visit trip', 'walk', 'active')
                RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
    }

    private void member(long tripId, long userId) {
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
    }

    private void endTrip(long tripId, Instant endedAt) {
        jdbc.sql("UPDATE trips SET mode = 'ended', ended_at = :endedAt WHERE id = :tripId")
            .param("endedAt", java.sql.Timestamp.from(endedAt))
            .param("tripId", tripId)
            .update();
    }

    private long visitCount(long tripId) {
        return jdbc.sql("SELECT COUNT(*) FROM visits WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }
}
