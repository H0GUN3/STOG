package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.auth.AccessTokenService;
import com.stog.backend.auth.UserRepository;
import com.stog.backend.plan.TripCoverService;
import com.stog.backend.plan.TripMembershipPolicy;
import com.stog.backend.plan.TripRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
    PhotoHttpIntegrationTest.StorageTestConfiguration.class,
    TripCoverHttpIntegrationTest.TripCoverTestConfiguration.class
})
class TripCoverHttpIntegrationTest {
    private static final byte[] COVER = "trip-cover-http-payload".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private PhotoHttpIntegrationTest.HttpGcsSurface storage;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void cleanup() {
        jdbc.sql(
                "DELETE FROM trips WHERE owner_id IN "
                    + "(SELECT id FROM users WHERE nickname LIKE 'trip-cover-http-%')"
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'trip-cover-http-%'").update();
        storage.clear();
    }

    @Test
    void createUploadFinalizeAndRequeryCoverOverHttp() throws Exception {
        long userId = user();
        String token = accessTokens.issue(users.findById(userId).orElseThrow());

        HttpResponse<String> created = request(
            "POST",
            "/trips",
            token,
            """
                {
                  "title": "HTTP 대표 이미지 여행",
                  "activity_type": "tour",
                  "planned_start_date": "2026-09-01",
                  "planned_end_date": "2026-09-03"
                }
                """
        );
        assertThat(created.statusCode()).isEqualTo(200);
        long tripId = json.readTree(created.body()).path("id").asLong();

        String uploadId = UUID.randomUUID().toString();
        String digest = sha256(COVER);
        String uploadBody = """
            {
              "client_upload_id": "%s",
              "content_type": "image/jpeg",
              "size_bytes": %d,
              "sha256": "%s"
            }
            """.formatted(uploadId, COVER.length, digest);
        HttpResponse<String> uploadUrl = request(
            "POST",
            "/trips/" + tripId + "/cover/upload-url",
            token,
            uploadBody
        );
        assertThat(uploadUrl.statusCode()).isEqualTo(200);
        JsonNode upload = json.readTree(uploadUrl.body());
        assertThat(upload.path("object_key").asText())
            .startsWith("trip-covers/accounts/" + userId + "/trips/" + tripId + "/uploads/");
        assertThat(put(upload, COVER)).isEqualTo(200);

        HttpResponse<String> finalized = request(
            "POST",
            "/trips/" + tripId + "/cover/finalize",
            token,
            uploadBody
        );
        assertThat(finalized.statusCode()).isEqualTo(204);

        HttpResponse<String> detail = request("GET", "/trips/" + tripId, token, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        String detailCoverUrl = json.readTree(detail.body()).path("cover_image_url").asText();
        assertThat(detailCoverUrl).startsWith("http://127.0.0.1:");

        HttpResponse<String> trips = request("GET", "/trips/me", token, null);
        assertThat(trips.statusCode()).isEqualTo(200);
        assertThat(json.readTree(trips.body()).findValue("cover_image_url").asText())
            .isEqualTo(detailCoverUrl);

        HttpResponse<String> home = request("GET", "/trips/me/home", token, null);
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(json.readTree(home.body()).path("trips").findValue("cover_image_url").asText())
            .isEqualTo(detailCoverUrl);
        assertThat(storage.objectCount()).isEqualTo(1);
    }

    @Test
    void coverUploadRejectsWrongDigestBeforePersistence() throws Exception {
        long userId = user();
        String token = accessTokens.issue(users.findById(userId).orElseThrow());
        HttpResponse<String> created = request(
            "POST",
            "/trips",
            token,
            """
                {
                  "title": "HTTP 잘못된 대표 이미지",
                  "activity_type": "tour"
                }
                """
        );
        long tripId = json.readTree(created.body()).path("id").asLong();

        HttpResponse<String> response = request(
            "POST",
            "/trips/" + tripId + "/cover/upload-url",
            token,
            """
                {
                  "client_upload_id": "%s",
                  "content_type": "image/jpeg",
                  "size_bytes": %d,
                  "sha256": "%s"
                }
                """.formatted(UUID.randomUUID(), COVER.length, "0".repeat(64))
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(put(json.readTree(response.body()), "different".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(400);
        assertThat(storage.objectCount()).isZero();
    }

    private int put(JsonNode upload, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(upload.path("upload_url").asText())
            )
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        upload.path("upload_headers").fields().forEachRemaining(entry ->
            request.header(entry.getKey(), entry.getValue().asText())
        );
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
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
            case "POST" -> builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long user() {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "trip-cover-http-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TripCoverTestConfiguration {
        @Bean
        TripCoverService tripCoverService(
            TripMembershipPolicy memberships,
            TripRepository trips,
            GcsSignedUrlService storage
        ) {
            return new TripCoverService(memberships, trips, storage);
        }
    }
}
