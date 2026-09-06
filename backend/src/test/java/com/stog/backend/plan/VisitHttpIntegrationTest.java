package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.auth.AccessTokenService;
import com.stog.backend.auth.UserRepository;
import com.stog.backend.cell.CellIdCalculator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VisitHttpIntegrationTest {
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;
    private static final String CELL_ID = CellIdCalculator.toWire(
        CellIdCalculator.fromCoords(LATITUDE, LONGITUDE)
    );
    private static final Instant ENTERED = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant LEFT = ENTERED.plus(Duration.ofMinutes(30));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlanningService planning;

    @Autowired
    private TripMembershipRepository memberships;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService accessTokens;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @AfterEach
    void cleanup() {
        jdbc.sql(
                """
                DELETE FROM trips
                WHERE owner_id IN (
                    SELECT id FROM users WHERE nickname LIKE 'todo4-http-%'
                )
                """
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'todo4-http-%'").update();
    }

    @Test
    void realHttpSurfaceCoversReplayIsolationAndLateEventPolicy() throws Exception {
        long ownerId = user("owner");
        long memberId = user("member");
        long tripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("HTTP visit trip", "tour", null, null)
        ).id();
        memberships.activateMember(tripId, memberId);
        String ownerToken = token(ownerId);
        String memberToken = token(memberId);
        UUID sharedKey = UUID.randomUUID();
        String fingerprint = fingerprint(ENTERED, LEFT);

        HttpResponse<String> created = postVisit(
            ownerToken,
            tripId,
            body(sharedKey, fingerprint, ENTERED, LEFT)
        );
        HttpResponse<String> replay = postVisit(
            ownerToken,
            tripId,
            reorderedBody(sharedKey, fingerprint, ENTERED, LEFT)
        );
        Instant changedLeft = LEFT.plus(Duration.ofMinutes(1));
        HttpResponse<String> changed = postVisit(
            ownerToken,
            tripId,
            body(sharedKey, fingerprint(ENTERED, changedLeft), ENTERED, changedLeft)
        );
        HttpResponse<String> memberSameCellTime = postVisit(
            memberToken,
            tripId,
            body(sharedKey, fingerprint, ENTERED, LEFT)
        );

        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(changed.body()).at("/error/code").asText())
            .isEqualTo("VISIT_IDEMPOTENCY_CONFLICT");
        assertThat(memberSameCellTime.statusCode()).isEqualTo(200);
        assertThat(visitCount(tripId)).isEqualTo(2);

        HttpResponse<String> ended = patch(
            ownerToken,
            "/trips/" + tripId + "/mode",
            "{\"mode\":\"ended\"}"
        );
        assertThat(ended.statusCode()).isEqualTo(200);
        Instant endedAt = Instant.parse(
            objectMapper.readTree(ended.body()).path("ended_at").asText()
        );
        HttpResponse<String> replayAfterEnd = postVisit(
            ownerToken,
            tripId,
            body(sharedKey, fingerprint, ENTERED, LEFT)
        );
        assertThat(replayAfterEnd.statusCode()).isEqualTo(200);
        assertThat(replayAfterEnd.body()).isEqualTo(created.body());

        Instant lateEntered = endedAt.minus(Duration.ofMinutes(30));
        HttpResponse<String> qualifyingLate = postVisit(
            memberToken,
            tripId,
            body(
                UUID.randomUUID(),
                fingerprint(lateEntered, endedAt),
                lateEntered,
                endedAt
            )
        );
        assertThat(qualifyingLate.statusCode()).isEqualTo(200);
        assertThat(visitCount(tripId)).isEqualTo(3);

        long expiredTripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("Expired HTTP visit trip", "tour", null, null)
        ).id();
        Instant expiredEnd = Instant.now()
            .minus(Duration.ofHours(25))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.sql(
                """
                UPDATE trips
                SET mode = 'ended', ended_at = :endedAt
                WHERE id = :tripId
                """
            )
            .param("endedAt", Timestamp.from(expiredEnd))
            .param("tripId", expiredTripId)
            .update();
        Instant expiredEntered = expiredEnd.minus(Duration.ofMinutes(30));
        HttpResponse<String> expiredLate = postVisit(
            ownerToken,
            expiredTripId,
            body(
                UUID.randomUUID(),
                fingerprint(expiredEntered, expiredEnd),
                expiredEntered,
                expiredEnd
            )
        );
        assertThat(expiredLate.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(expiredLate.body()).at("/error/code").asText())
            .isEqualTo("VISIT_LATE_WINDOW_EXPIRED");
        assertThat(visitCount(expiredTripId)).isZero();

        System.out.println(
            "HTTP_QA new=" + created.statusCode()
                + " replay=" + replay.statusCode()
                + " changed=" + changed.statusCode()
                + ":VISIT_IDEMPOTENCY_CONFLICT"
                + " member=" + memberSameCellTime.statusCode()
                + " ended_replay=" + replayAfterEnd.statusCode()
                + " late=" + qualifyingLate.statusCode()
                + " expired=" + expiredLate.statusCode()
                + ":VISIT_LATE_WINDOW_EXPIRED"
                + " rows=" + visitCount(tripId)
        );
    }

    private HttpResponse<String> postVisit(
        String token,
        long tripId,
        String body
    ) throws Exception {
        return request(token, "/trips/" + tripId + "/visits", "POST", body);
    }

    private HttpResponse<String> patch(String token, String path, String body) throws Exception {
        return request(token, path, "PATCH", body);
    }

    private HttpResponse<String> request(
        String token,
        String path,
        String method,
        String body
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String body(
        UUID clientVisitId,
        String fingerprint,
        Instant enteredAt,
        Instant leftAt
    ) {
        return """
            {
              "client_visit_id":"%s",
              "payload_fingerprint":"%s",
              "cell_id":"%s",
              "lat":%s,
              "lng":%s,
              "entered_at":"%s",
              "left_at":"%s",
              "status":"visited",
              "is_interpolated":false
            }
            """.formatted(
                clientVisitId,
                fingerprint,
                CELL_ID,
                LATITUDE,
                LONGITUDE,
                enteredAt,
                leftAt
            );
    }

    private String reorderedBody(
        UUID clientVisitId,
        String fingerprint,
        Instant enteredAt,
        Instant leftAt
    ) {
        return """
            {
              "status":"visited",
              "lng":%s,
              "left_at":"%s",
              "payload_fingerprint":"%s",
              "client_visit_id":"%s",
              "is_interpolated":false,
              "cell_id":"%s",
              "entered_at":"%s",
              "lat":%s
            }
            """.formatted(
                LONGITUDE,
                leftAt,
                fingerprint,
                clientVisitId,
                CELL_ID,
                enteredAt,
                LATITUDE
            );
    }

    private String fingerprint(Instant enteredAt, Instant leftAt) {
        return VisitServiceTest.fingerprint(
            CELL_ID,
            LATITUDE,
            LONGITUDE,
            enteredAt,
            leftAt,
            "visited",
            false
        );
    }

    private String token(long userId) {
        return accessTokens.issue(users.findById(userId).orElseThrow());
    }

    private long user(String suffix) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "todo4-http-" + suffix + "-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    private long visitCount(long tripId) {
        return jdbc.sql("SELECT COUNT(*) FROM visits WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query(Long.class)
            .single();
    }
}
