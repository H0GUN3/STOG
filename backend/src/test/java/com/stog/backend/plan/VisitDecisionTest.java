package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class VisitDecisionTest {
    private static final Instant ENTERED = Instant.parse("2026-08-20T09:00:00Z");
    private static final VisitDecision.Rules RULES = new VisitDecision.Rules(
        Duration.ofMinutes(30),
        6.0
    );

    @Autowired
    private VisitService visits;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void classificationIsDeterministicAtDwellAndSpeedBoundaries() {
        VisitDecision.Result shortStay = VisitDecision.classify(
            ENTERED,
            ENTERED.plus(Duration.ofMinutes(29)),
            false,
            RULES
        );
        VisitDecision.Result qualifyingStay = VisitDecision.classify(
            ENTERED,
            ENTERED.plus(Duration.ofMinutes(30)),
            false,
            RULES
        );
        VisitDecision.Result interpolated = VisitDecision.classify(
            ENTERED,
            ENTERED.plus(Duration.ofMinutes(45)),
            true,
            RULES
        );

        assertThat(shortStay.status()).isEqualTo(VisitDecision.Status.PASSED);
        assertThat(shortStay.rewardEligible()).isFalse();
        assertThat(qualifyingStay.status()).isEqualTo(VisitDecision.Status.VISITED);
        assertThat(qualifyingStay.rewardEligible()).isTrue();
        assertThat(interpolated.status()).isEqualTo(VisitDecision.Status.PASSED);
        assertThat(interpolated.rewardEligible()).isFalse();
        assertThat(VisitDecision.isInterpolated(
            100.0,
            ENTERED,
            ENTERED.plus(Duration.ofMinutes(1)),
            RULES
        )).isFalse();
        assertThat(VisitDecision.isInterpolated(
            101.0,
            ENTERED,
            ENTERED.plus(Duration.ofMinutes(1)),
            RULES
        )).isTrue();
    }

    @Test
    void boundaryValidatesCoordinateCellAndRejectsInterpolatedVisitRecords() {
        long ownerId = user("visit-owner");
        long tripId = trip(ownerId);
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        VisitRequests.Record valid = request(CellIdCalculator.toWire(cellId), false);

        assertThatThrownBy(() -> visits.record(
            ownerId,
            tripId,
            request(CellIdCalculator.toWire(CellIdCalculator.fromCoords(35.9, 127.3)), false)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> visits.record(ownerId, tripId, request(valid.cell_id(), true)))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM visits WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isZero();

        VisitResponses.Recorded recorded = visits.record(ownerId, tripId, valid);

        assertThat(recorded.cell_id()).isEqualTo(valid.cell_id());
        assertThat(recorded.status()).isEqualTo("visited");
        assertThat(recorded.is_interpolated()).isFalse();
    }

    private VisitRequests.Record request(String cellId, boolean interpolated) {
        Instant leftAt = ENTERED.plus(Duration.ofMinutes(30));
        return new VisitRequests.Record(
            java.util.UUID.randomUUID(),
            VisitServiceTest.fingerprint(
                cellId,
                35.815,
                127.15,
                ENTERED,
                leftAt,
                "visited",
                interpolated
            ),
            cellId,
            35.815,
            127.15,
            ENTERED,
            leftAt,
            "visited",
            interpolated
        );
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId) {
        return jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, 'visit trip', 'walk')
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
    }
}
