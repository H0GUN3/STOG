package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.auth.AccessTokenService;
import com.stog.backend.auth.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripCrudHttpIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService accessTokens;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void cleanup() {
        jdbc.sql(
                """
                DELETE FROM trips
                WHERE owner_id IN (
                    SELECT id FROM users WHERE nickname LIKE 'trip-crud-http-%'
                )
                """
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'trip-crud-http-%'").update();
    }

    @Test
    void liveHttpRoundTripPersistsTripUpdateAndBasketRemoval() throws Exception {
        long userId = user();
        String token = accessTokens.issue(users.findById(userId).orElseThrow());

        HttpResponse<String> created = request(
            "POST",
            "/trips",
            token,
            """
                {
                  "title": "HTTP 여행",
                  "activity_type": "tour",
                  "planned_start_date": "2026-09-01",
                  "planned_end_date": "2026-09-03"
                }
                """
        );
        assertThat(created.statusCode()).isEqualTo(200);
        long tripId = json.readTree(created.body()).path("id").asLong();

        HttpResponse<String> updated = request(
            "PUT",
            "/trips/" + tripId,
            token,
            """
                {
                  "title": "HTTP 수정 여행",
                  "planned_start_date": "2026-09-10",
                  "planned_end_date": "2026-09-12"
                }
                """
        );
        assertThat(updated.statusCode()).isEqualTo(200);

        HttpResponse<String> detail = request("GET", "/trips/" + tripId, token, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).path("title").asText())
            .isEqualTo("HTTP 수정 여행");
        assertThat(json.readTree(detail.body()).path("planned_start_date").asText())
            .isEqualTo("2026-09-10");

        String clientItemId = UUID.randomUUID().toString();
        String title = "HTTP 삭제 장소";
        String originalUrl = "https://example.test/http-place";
        String fingerprint = BasketPayloadFingerprint.forLink(
            tripId,
            "share",
            originalUrl,
            title,
            null
        );
        HttpResponse<String> added = request(
            "POST",
            "/basket-items/link",
            token,
            """
                {
                  "trip_id": %d,
                  "client_item_id": "%s",
                  "payload_fingerprint": "%s",
                  "source": "share",
                  "original_url": "%s",
                  "title": "%s"
                }
                """.formatted(tripId, clientItemId, fingerprint, originalUrl, title)
        );
        assertThat(added.statusCode()).isEqualTo(200);
        long basketItemId = json.readTree(added.body()).path("id").asLong();

        HttpResponse<String> removed = request(
            "DELETE",
            "/trips/" + tripId + "/basket/" + basketItemId,
            token,
            null
        );
        assertThat(removed.statusCode()).isEqualTo(204);

        HttpResponse<String> basket = request(
            "GET",
            "/trips/" + tripId + "/basket",
            token,
            null
        );
        assertThat(basket.statusCode()).isEqualTo(200);
        assertThat(json.readTree(basket.body())).isEmpty();
    }

    private HttpResponse<String> request(
        String method,
        String path,
        String token,
        String body
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token);
        HttpRequest request = switch (method) {
            case "GET" -> builder.GET().build();
            case "DELETE" -> builder.DELETE().build();
            case "POST", "PUT" -> builder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long user() {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "trip-crud-http-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }
}
