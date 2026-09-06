package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stog.backend.cell.CellIdCalculator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VisitControllerTest {
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;
    private static final String CELL_ID = CellIdCalculator.toWire(
        CellIdCalculator.fromCoords(LATITUDE, LONGITUDE)
    );
    private static final Instant ENTERED = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LEFT = ENTERED.plus(Duration.ofMinutes(30));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void newWriteAndReorderedIdenticalReplayReturnTheOriginalResponse() throws Exception {
        long ownerId = user("controller-owner");
        long tripId = trip(ownerId);
        UUID clientVisitId = UUID.randomUUID();
        String fingerprint = VisitServiceTest.fingerprint(
            CELL_ID, LATITUDE, LONGITUDE, ENTERED, LEFT, "visited", false
        );
        String firstBody = requestBody(clientVisitId, fingerprint, LEFT);

        String firstResponse = mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.client_visit_id").value(clientVisitId.toString()))
            .andExpect(jsonPath("$.payload_fingerprint").value(fingerprint))
            .andExpect(jsonPath("$.review_required").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        String reorderedBody = """
            {
              "status": "visited",
              "left_at": "%s",
              "client_visit_id": "%s",
              "lng": %s,
              "payload_fingerprint": "%s",
              "is_interpolated": false,
              "entered_at": "%s",
              "lat": %s,
              "cell_id": "%s"
            }
            """.formatted(
                LEFT,
                clientVisitId,
                LONGITUDE,
                fingerprint,
                ENTERED,
                LATITUDE,
                CELL_ID
            );
        String replayResponse = mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(reorderedBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM visits WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single()).isEqualTo(1);
    }

    @Test
    void passedVisitReturnsDurableReviewReceiptWithoutPrompt() throws Exception {
        long ownerId = user("controller-passed-owner");
        long tripId = trip(ownerId);
        UUID clientVisitId = UUID.randomUUID();
        Instant passedLeft = ENTERED.plus(Duration.ofMinutes(29));
        String fingerprint = VisitServiceTest.fingerprint(
            CELL_ID, LATITUDE, LONGITUDE, ENTERED, passedLeft, "passed", false
        );
        String body = requestBody(clientVisitId, fingerprint, passedLeft)
            .replace("\"status\": \"visited\"", "\"status\": \"passed\"");

        mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.review_required").value(false));

        assertThat(jdbc.sql(
                "SELECT review_required FROM visit_review_receipts WHERE visit_id = (SELECT id FROM visits WHERE trip_id = :tripId)"
            )
            .param("tripId", tripId)
            .query(Boolean.class)
            .single()).isFalse();
    }

    @Test
    void changedPayloadReturnsTypedIdempotencyConflict() throws Exception {
        long ownerId = user("controller-conflict-owner");
        long tripId = trip(ownerId);
        UUID clientVisitId = UUID.randomUUID();
        String originalFingerprint = VisitServiceTest.fingerprint(
            CELL_ID, LATITUDE, LONGITUDE, ENTERED, LEFT, "visited", false
        );
        mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(clientVisitId, originalFingerprint, LEFT)))
            .andExpect(status().isOk());

        Instant changedLeft = LEFT.plus(Duration.ofMinutes(1));
        String changedFingerprint = VisitServiceTest.fingerprint(
            CELL_ID, LATITUDE, LONGITUDE, ENTERED, changedLeft, "visited", false
        );
        mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(ownerId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(clientVisitId, changedFingerprint, changedLeft)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("VISIT_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void malformedUuidFingerprintAndTimestampFailAtTheHttpBoundary() throws Exception {
        long ownerId = user("controller-malformed-owner");
        long tripId = trip(ownerId);

        assertBadRequest(ownerId, tripId, """
            {
              "client_visit_id": "not-a-uuid",
              "payload_fingerprint": "%s",
              "cell_id": "%s",
              "lat": %s,
              "lng": %s,
              "entered_at": "%s",
              "left_at": "%s",
              "status": "visited",
              "is_interpolated": false
            }
            """.formatted("0".repeat(64), CELL_ID, LATITUDE, LONGITUDE, ENTERED, LEFT));
        assertBadRequest(ownerId, tripId, requestBody(UUID.randomUUID(), "ABC", LEFT));
        assertBadRequest(
            ownerId,
            tripId,
            requestBody(UUID.randomUUID(), "0".repeat(64), LEFT)
                .replace(ENTERED.toString(), "not-a-timestamp")
        );
    }

    private void assertBadRequest(long userId, long tripId, String body) throws Exception {
        mockMvc.perform(post("/trips/{tripId}/visits", tripId)
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    private String requestBody(UUID clientVisitId, String fingerprint, Instant leftAt) {
        return """
            {
              "client_visit_id": "%s",
              "payload_fingerprint": "%s",
              "cell_id": "%s",
              "lat": %s,
              "lng": %s,
              "entered_at": "%s",
              "left_at": "%s",
              "status": "visited",
              "is_interpolated": false
            }
            """.formatted(
                clientVisitId,
                fingerprint,
                CELL_ID,
                LATITUDE,
                LONGITUDE,
                ENTERED,
                leftAt
            );
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long trip(long ownerId) {
        return jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type, mode)
                VALUES (:ownerId, 'controller visit trip', 'walk', 'active')
                RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
    }
}
