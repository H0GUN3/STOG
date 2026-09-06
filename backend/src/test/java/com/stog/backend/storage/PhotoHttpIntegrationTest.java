package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.auth.AccessTokenService;
import com.stog.backend.auth.UserRepository;
import com.stog.backend.plan.EffectiveVisibilityService;
import com.stog.backend.plan.PlanningService;
import com.stog.backend.plan.TripMembershipPolicy;
import com.stog.backend.plan.TripRequests;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
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
@Import(PhotoHttpIntegrationTest.StorageTestConfiguration.class)
class PhotoHttpIntegrationTest {
    private static final byte[] ORIGINAL = "task10-original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMBNAIL = "task10-thumbnail".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlanningService planning;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private HttpGcsSurface storage;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @AfterEach
    void cleanup() {
        jdbc.sql(
                """
                DELETE FROM cell_stats
                WHERE landmark_count = 0
                  AND cell_id IN (
                      SELECT DISTINCT cell_id
                      FROM photos
                      WHERE user_id IN (
                          SELECT id FROM users WHERE nickname LIKE 'todo10-http-%'
                      )
                  )
                """
            )
            .update();
        jdbc.sql(
                """
                UPDATE cell_stats
                SET top_photo_id = NULL
                WHERE top_photo_id IN (
                    SELECT id FROM photos
                    WHERE user_id IN (
                        SELECT id FROM users WHERE nickname LIKE 'todo10-http-%'
                    )
                )
                """
            )
            .update();
        jdbc.sql(
                "DELETE FROM photos WHERE user_id IN (SELECT id FROM users WHERE nickname LIKE 'todo10-http-%')"
            )
            .update();
        jdbc.sql(
                "DELETE FROM trips WHERE owner_id IN (SELECT id FROM users WHERE nickname LIKE 'todo10-http-%')"
            )
            .update();
        jdbc.sql("DELETE FROM users WHERE nickname LIKE 'todo10-http-%'").update();
        jdbc.sql("UPDATE places SET catalog_status = 'quarantined' "
                + "WHERE external_id LIKE 'todo10-http-%'")
            .update();
        jdbc.sql("DELETE FROM places WHERE external_id LIKE 'todo10-http-%'").update();
        jdbc.sql("DELETE FROM license_snapshots WHERE digest LIKE 'todo10-http-%'").update();
        jdbc.sql("DELETE FROM catalog_sources WHERE source_key LIKE 'todo10-http-%'").update();
        storage.clear();
        long remaining = jdbc.sql(
                "SELECT COUNT(*) FROM users WHERE nickname LIKE 'todo10-http-%'"
            )
            .query(Long.class)
            .single();
        System.out.println(
            "PHOTO_HTTP_CLEANUP users=" + remaining + " objects=" + storage.objectCount()
        );
    }

    @Test
    void directPairFinalizeReadArchiveAndBadPathsUseRealHttpSurfaces() throws Exception {
        long ownerId = user("owner");
        long outsiderId = user("outsider");
        long tripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("Todo 10 HTTP trip", "tour", null, null)
        ).id();
        String ownerToken = token(ownerId);
        String outsiderToken = token(outsiderId);
        long placeId = canonicalPlace("HTTP immutable place", 35.815, 127.15, true);
        HttpResponse<String> preview = request(
            ownerToken,
            "/photos/place-preview",
            "POST",
            """
            {"latitude":35.815,"longitude":127.15,"accuracy_m":12.5}
            """
        );
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode previewJson = json.readTree(preview.body());
        assertThat(previewJson.path("status").asText()).isEqualTo("matched");
        assertThat(previewJson.path("place_id").asLong()).isEqualTo(placeId);
        assertThat(previewJson.path("place_name").asText()).isEqualTo("HTTP immutable place");

        String uploadId = "33333333-3333-3333-3333-333333333333";
        String issueBody = uploadBody(tripId, uploadId);

        HttpResponse<String> issue = request(ownerToken, "/photos/upload-url", "POST", issueBody);
        assertThat(issue.statusCode()).isEqualTo(200);
        assertThat(photoCount(uploadId)).isZero();
        assertThat(uploadSessionCount(uploadId)).isZero();
        JsonNode urls = json.readTree(issue.body());
        assertThat(put(urls, "original", ORIGINAL)).isEqualTo(200);
        assertThat(put(urls, "thumbnail", THUMBNAIL)).isEqualTo(200);

        String finalizeBody = withMatchedSetLog(
            finalizeBody(tripId, uploadId, urls), placeId
        );
        HttpResponse<String> finalized = request(ownerToken, "/photos", "POST", finalizeBody);
        HttpResponse<String> duplicate = request(ownerToken, "/photos", "POST", finalizeBody);
        assertThat(finalized.statusCode()).isEqualTo(200);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        JsonNode finalizedJson = json.readTree(finalized.body());
        assertThat(json.readTree(duplicate.body())).isEqualTo(finalizedJson);
        long photoId = finalizedJson.path("id").asLong();
        String cellId = finalizedJson.path("cell_id").asText();
        assertThat(cellId).isNotBlank();
        assertThat(photoCount(uploadId)).isEqualTo(1);

        HttpResponse<String> detail = request(ownerToken, "/photos/" + photoId, "GET", null);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode detailJson = json.readTree(detail.body());
        assertThat(detailJson.path("accuracy_m").asDouble()).isEqualTo(12.5);
        assertThat(detailJson.path("caption").asText()).isEqualTo("HTTP Set Log note");
        assertThat(detailJson.path("place_id").asLong()).isEqualTo(placeId);
        assertThat(detailJson.path("place_name").asText()).isEqualTo("HTTP immutable place");
        assertThat(detailJson.path("place_resolution_status").asText()).isEqualTo("matched");
        assertThat(detailJson.path("visibility").asText()).isEqualTo("public");
        assertThat(detailJson.path("moderation_status").asText()).isEqualTo("pending");
        assertThat(detailJson.path("publication_status").asText()).isEqualTo("public");
        assertThat(read(URI.create(detailJson.path("original_url").asText())))
            .isEqualTo(ORIGINAL);
        assertThat(read(URI.create(detailJson.path("thumbnail_url").asText())))
            .isEqualTo(THUMBNAIL);

        HttpResponse<String> archive = request(
            ownerToken,
            "/trips/" + tripId + "/photos",
            "GET",
            null
        );
        assertThat(archive.statusCode()).isEqualTo(200);
        JsonNode archiveItem = json.readTree(archive.body()).get(0);
        assertThat(archiveItem.path("id").asLong()).isEqualTo(photoId);
        assertThat(archiveItem.path("place_name").asText()).isEqualTo("HTTP immutable place");
        assertThat(archiveItem.path("moderation_status").asText()).isEqualTo("pending");

        HttpResponse<String> cell = request(
            ownerToken,
            "/cells/" + cellId + "/photos",
            "GET",
            null
        );
        assertThat(cell.statusCode()).isEqualTo(200);
        JsonNode cellItem = json.readTree(cell.body()).at("/items/0");
        assertThat(cellItem.path("id").asLong()).isEqualTo(photoId);
        assertThat(cellItem.path("cell_id").asText()).isEqualTo(cellId);
        assertThat(cellItem.path("caption").asText()).isEqualTo("HTTP Set Log note");
        assertThat(cellItem.path("place_id").asLong()).isEqualTo(placeId);
        assertThat(cellItem.path("place_name").asText()).isEqualTo("HTTP immutable place");
        assertThat(cellItem.path("place_resolution_status").asText()).isEqualTo("matched");
        assertThat(cellItem.path("visibility").asText()).isEqualTo("public");
        assertThat(cellItem.path("publication_status").asText()).isEqualTo("public");

        jdbc.sql("UPDATE places SET name = 'Renamed HTTP place', "
                + "catalog_status = 'quarantined' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        JsonNode afterDelete = json.readTree(request(
            ownerToken, "/photos/" + photoId, "GET", null
        ).body());
        assertThat(afterDelete.path("place_id").isNull()).isTrue();
        assertThat(afterDelete.path("place_name").asText()).isEqualTo("HTTP immutable place");
        assertThat(request(
            outsiderToken,
            "/trips/" + tripId + "/photos",
            "GET",
            null
        ).statusCode()).isEqualTo(200);

        storage.remove(urls.path("original_object_key").asText());
        storage.remove(urls.path("thumbnail_object_key").asText());
        jdbc.sql("UPDATE photos SET trip_id = NULL WHERE id = :photoId")
            .param("photoId", photoId)
            .update();
        HttpResponse<String> archivedReplay = request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody
        );
        assertThat(archivedReplay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(archivedReplay.body())).isEqualTo(json.readTree(finalized.body()));
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody.replace("\"source\":\"camera\"", "\"source\":\"gallery\"")
        ).statusCode()).isEqualTo(409);
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody.replace(sha256(ORIGINAL), "f".repeat(64))
        ).statusCode()).isEqualTo(409);
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody.replace("/original.jpg", "/changed.jpg")
        ).statusCode()).isEqualTo(409);
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody.replace("\"trip_id\":" + tripId, "\"trip_id\":" + (tripId + 1L))
        ).statusCode()).isEqualTo(409);

        String missingId = "44444444-4444-4444-4444-444444444444";
        JsonNode missingUrls = json.readTree(request(
            ownerToken,
            "/photos/upload-url",
            "POST",
            uploadBody(tripId, missingId)
        ).body());
        assertThat(put(missingUrls, "original", ORIGINAL)).isEqualTo(200);
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody(tripId, missingId, missingUrls)
        ).statusCode()).isEqualTo(400);
        assertThat(photoCount(missingId)).isZero();

        String wrongId = "55555555-5555-5555-5555-555555555555";
        JsonNode wrongUrls = json.readTree(request(
            ownerToken,
            "/photos/upload-url",
            "POST",
            uploadBody(tripId, wrongId)
        ).body());
        assertThat(put(wrongUrls, "original", ORIGINAL)).isEqualTo(200);
        assertThat(put(wrongUrls, "thumbnail", THUMBNAIL)).isEqualTo(200);
        storage.replaceMetadata(
            wrongUrls.path("original_object_key").asText(),
            "stog-sha256",
            "f".repeat(64)
        );
        assertThat(request(
            ownerToken,
            "/photos",
            "POST",
            finalizeBody(tripId, wrongId, wrongUrls)
        ).statusCode()).isEqualTo(400);
        assertThat(photoCount(wrongId)).isZero();

        assertThat(request(
            outsiderToken,
            "/photos",
            "POST",
            finalizeBody(tripId, uploadId, urls)
        ).statusCode()).isEqualTo(403);

        String stored = jdbc.sql(
                "SELECT original_key || '|' || thumb_key FROM photos WHERE id = :photoId"
            )
            .param("photoId", photoId)
            .query(String.class)
            .single();
        assertThat(stored).doesNotContain("http://", "https://");
        assertThat(storage.objectCount()).isEqualTo(3);
        System.out.println(
            "PHOTO_HTTP_QA preview=200 issue=200 puts=200,200 finalize=200 exact_replay=200"
                + " detail=200 archive=200 cell=200 archived_replay=200"
                + " changed_replays=409,409,409,409 unauthorized=403 missing=400 wrong_metadata=400"
                + " rows=1 objects=" + storage.objectCount()
        );
        System.out.println(
            "PHOTO_HTTP_OBJECT_PREFIX="
                + urls.path("original_object_key").asText().replace("/original.jpg", "/")
        );
    }

    private HttpResponse<String> unauthenticatedRequest(
        String path,
        String method,
        String body
    ) throws Exception {
        return http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private int put(JsonNode urls, String kind, byte[] body) throws Exception {
        JsonNode headers = urls.path(kind + "_upload_headers");
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(urls.path(kind + "_upload_url").asText())
            )
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.properties().forEach(entry -> request.header(
            entry.getKey(),
            entry.getValue().asText()
        ));
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private byte[] read(URI uri) throws Exception {
        return http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).body();
    }

    private HttpResponse<String> request(
        String token,
        String path,
        String method,
        String body
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)
            )
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void placePreviewUsesOnlyNameLicensedCanonicalCandidates() throws Exception {
        long ownerId = user("place-preview-owner");
        String token = token(ownerId);
        canonicalPlace("Ineligible closer place", 35.815, 127.15, false);
        long expectedPlaceId = canonicalPlace(
            "Eligible named place", 35.81518, 127.15, true
        );

        HttpResponse<String> unauthorized = unauthenticatedRequest(
            "/photos/place-preview",
            "POST",
            """
            {"latitude":35.815,"longitude":127.15,"accuracy_m":12}
            """
        );
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        HttpResponse<String> preview = request(
            token,
            "/photos/place-preview",
            "POST",
            """
            {"latitude":35.815,"longitude":127.15,"accuracy_m":12}
            """
        );
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(preview.body());
        assertThat(body.path("status").asText()).isEqualTo("matched");
        assertThat(body.path("place_id").asLong()).isEqualTo(expectedPlaceId);
        assertThat(body.path("place_name").asText()).isEqualTo("Eligible named place");

        HttpResponse<String> inaccurate = request(
            token,
            "/photos/place-preview",
            "POST",
            """
            {"latitude":35.815,"longitude":127.15,"accuracy_m":100.01}
            """
        );
        assertThat(inaccurate.statusCode()).isEqualTo(200);
        assertThat(json.readTree(inaccurate.body()).path("status").asText())
            .isEqualTo("no_match");
    }

    @Test
    void concurrentExactFinalizeConvergesOnOneDurablePhoto() throws Exception {
        long ownerId = user("concurrent-owner");
        long tripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("Concurrent photo", "tour", null, null)
        ).id();
        String token = token(ownerId);
        String uploadId = "66666666-6666-6666-6666-666666666666";
        JsonNode urls = json.readTree(request(
            token,
            "/photos/upload-url",
            "POST",
            uploadBody(tripId, uploadId)
        ).body());
        assertThat(put(urls, "original", ORIGINAL)).isEqualTo(200);
        assertThat(put(urls, "thumbnail", THUMBNAIL)).isEqualTo(200);
        jdbc.sql("UPDATE trips SET visibility = 'public' WHERE id = :tripId")
            .param("tripId", tripId)
            .update();
        String body = withPublicIntent(finalizeBody(tripId, uploadId, urls));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return request(token, "/photos", "POST", body);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return request(token, "/photos", "POST", body);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            HttpResponse<String> firstResponse = first.get(10, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResponse.statusCode()).isEqualTo(200);
            assertThat(secondResponse.statusCode()).isEqualTo(200);
            assertThat(json.readTree(firstResponse.body()).path("id").asLong())
                .isEqualTo(json.readTree(secondResponse.body()).path("id").asLong());
            assertThat(photoCount(uploadId)).isEqualTo(1);
            long photoId = json.readTree(firstResponse.body()).path("id").asLong();
            assertThat(activeGrantCount(photoId)).isEqualTo(1L);
            assertThat(json.readTree(firstResponse.body()).path("publication_status").asText())
                .isEqualTo("public");

            jdbc.sql("UPDATE photos SET moderation_status = 'approved' WHERE id = :photoId")
                .param("photoId", photoId)
                .update();
            JsonNode approved = json.readTree(request(
                token, "/photos/" + photoId, "GET", null
            ).body());
            assertThat(approved.path("moderation_status").asText()).isEqualTo("approved");
            assertThat(approved.path("publication_status").asText()).isEqualTo("public");
            jdbc.sql("UPDATE photo_public_grants SET revoked_at = CURRENT_TIMESTAMP "
                    + "WHERE photo_id = :photoId AND revoked_at IS NULL")
                .param("photoId", photoId)
                .update();
            JsonNode revoked = json.readTree(request(
                token, "/photos/" + photoId, "GET", null
            ).body());
            assertThat(revoked.path("publication_status").asText()).isEqualTo("public");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void httpRejectsConfiguredSizeOverflowBeforeSigningOrFinalizing() throws Exception {
        long ownerId = user("size-owner");
        long tripId = planning.createTrip(
            ownerId,
            new TripRequests.Create("Size-bound photo", "tour", null, null)
        ).id();
        String token = token(ownerId);
        String uploadId = "77777777-7777-7777-7777-777777777777";
        long originalMax = 2L * 1024L * 1024L;
        long thumbnailMax = 50L * 1024L;

        assertThat(request(token, "/photos/upload-url", "POST", uploadBody(
            tripId, uploadId, originalMax, thumbnailMax
        )).statusCode()).isEqualTo(200);
        int exactPolicyCount = storage.signedPolicyCount();
        assertThat(request(token, "/photos/upload-url", "POST", uploadBody(
            tripId, uploadId, originalMax + 1L, thumbnailMax
        )).statusCode()).isEqualTo(400);
        assertThat(request(token, "/photos/upload-url", "POST", uploadBody(
            tripId, uploadId, originalMax, thumbnailMax + 1L
        )).statusCode()).isEqualTo(400);
        assertThat(request(token, "/photos/upload-url", "POST", uploadBody(
            tripId, uploadId, Long.MAX_VALUE, Long.MAX_VALUE
        )).statusCode()).isEqualTo(400);
        assertThat(storage.signedPolicyCount()).isEqualTo(exactPolicyCount);

        JsonNode urls = json.readTree(request(
            token,
            "/photos/upload-url",
            "POST",
            uploadBody(tripId, uploadId)
        ).body());
        String oversizedFinalize = finalizeBody(tripId, uploadId, urls)
            .replaceFirst(
                "\\\"size_bytes\\\":" + ORIGINAL.length,
                "\\\"size_bytes\\\":" + Long.MAX_VALUE
            );
        assertThat(request(token, "/photos", "POST", oversizedFinalize).statusCode())
            .isEqualTo(400);
        assertThat(photoCount(uploadId)).isZero();
        assertThat(uploadSessionCount(uploadId)).isZero();
    }

    private String uploadBody(long tripId, String uploadId) throws Exception {
        return uploadBody(tripId, uploadId, ORIGINAL.length, THUMBNAIL.length);
    }

    private String uploadBody(
        long tripId,
        String uploadId,
        long originalSize,
        long thumbnailSize
    ) throws Exception {
        return """
            {"trip_id":%d,"client_upload_id":"%s",
             "original":{"content_type":"image/jpeg","size_bytes":%d,"sha256":"%s"},
             "thumbnail":{"content_type":"image/jpeg","size_bytes":%d,"sha256":"%s"}}
            """.formatted(
            tripId,
            uploadId,
            originalSize,
            sha256(ORIGINAL),
            thumbnailSize,
            sha256(THUMBNAIL)
        );
    }

    private String finalizeBody(long tripId, String uploadId, JsonNode urls) throws Exception {
        return """
            {"trip_id":%d,"source":"camera","client_upload_id":"%s",
             "original_key":"%s","thumb_key":"%s",
             "original":{"content_type":"image/jpeg","size_bytes":%d,"sha256":"%s"},
             "thumbnail":{"content_type":"image/jpeg","size_bytes":%d,"sha256":"%s"},
             "latitude":35.815,"longitude":127.15,"taken_at":"2026-08-25T00:00:00Z"}
            """.formatted(
            tripId,
            uploadId,
            urls.path("original_object_key").asText(),
            urls.path("thumbnail_object_key").asText(),
            ORIGINAL.length,
            sha256(ORIGINAL),
            THUMBNAIL.length,
            sha256(THUMBNAIL)
        );
    }

    private String withMatchedSetLog(String body, long placeId) {
        int end = body.lastIndexOf('}');
        return body.substring(0, end)
            + ",\"accuracy_m\":12.5,\"location_provenance\":\"camera_foreground\""
            + ",\"caption\":\"HTTP Set Log note\",\"place_resolution_status\":\"matched\""
            + ",\"expected_place_id\":" + placeId
            + ",\"visibility\":\"private\",\"public_consent\":false}"
            + body.substring(end + 1);
    }

    private String withPublicIntent(String body) {
        int end = body.lastIndexOf('}');
        return body.substring(0, end)
            + ",\"visibility\":\"public\",\"public_consent\":true}"
            + body.substring(end + 1);
    }

    private long activeGrantCount(long photoId) {
        return jdbc.sql(
                "SELECT COUNT(*) FROM photo_public_grants "
                    + "WHERE photo_id = :photoId AND revoked_at IS NULL"
            )
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    private long photoCount(String uploadId) {
        return jdbc.sql("SELECT COUNT(*) FROM photos WHERE original_key LIKE :key")
            .param("key", "%/uploads/" + uploadId + "/original.jpg")
            .query(Long.class)
            .single();
    }

    private long uploadSessionCount(String uploadId) {
        return jdbc.sql(
                "SELECT COUNT(*) FROM photo_upload_sessions WHERE client_upload_id = :uploadId"
            )
            .param("uploadId", java.util.UUID.fromString(uploadId))
            .query(Long.class)
            .single();
    }

    private long canonicalPlace(
        String name,
        double latitude,
        double longitude,
        boolean nameReusable
    ) {
        String suffix = java.util.UUID.randomUUID().toString();
        long sourceId = jdbc.sql(
                "INSERT INTO catalog_sources (source_key, name, provider_type, active) "
                    + "VALUES (:key, 'HTTP photo fixture', 'public_data', TRUE) RETURNING id"
            )
            .param("key", "todo10-http-" + suffix)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'HTTP fixture license', CURRENT_TIMESTAMP,
                    DATE '2020-01-01', TRUE, CAST(:fields AS jsonb), :digest
                )
                RETURNING id
                """
            )
            .param("sourceId", sourceId)
            .param("fields", nameReusable ? "[\"name\"]" : "[\"address\"]")
            .param("digest", "todo10-http-license-" + suffix)
            .query(Long.class)
            .single();
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        long placeId = jdbc.sql(
                """
                INSERT INTO places (
                    name, lat, lng, source, external_id, normalized_name, compact_name
                )
                VALUES (
                    :name, :latitude, :longitude, 'public_data', :externalId,
                    :normalized, :compact
                )
                RETURNING id
                """
            )
            .param("name", name)
            .param("latitude", latitude)
            .param("longitude", longitude)
            .param("externalId", "todo10-http-place-" + suffix)
            .param("normalized", normalized)
            .param("compact", normalized.replace(" ", ""))
            .query(Long.class)
            .single();
        jdbc.sql(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (
                    :placeId, :sourceId, :licenseId, :externalId,
                    :digest, CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP
                )
                """
            )
            .param("placeId", placeId)
            .param("sourceId", sourceId)
            .param("licenseId", licenseId)
            .param("externalId", "todo10-http-record-" + suffix)
            .param("digest", "todo10-http-source-" + suffix)
            .update();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return placeId;
    }

    private long user(String suffix) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "todo10-http-" + suffix + "-" + java.util.UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    private String token(long userId) {
        return accessTokens.issue(users.findById(userId).orElseThrow());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean(destroyMethod = "close")
        HttpGcsSurface task10HttpGcsSurface() throws IOException {
            return new HttpGcsSurface();
        }

        @Bean
        GcsSignedUrlService task10GcsSignedUrlService(HttpGcsSurface objects) {
            return new GcsSignedUrlService(
                objects,
                new StorageProperties("task10-private-local", Duration.ofMinutes(5), "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
                Clock.systemUTC()
            );
        }

        @Bean
        PhotoService task10PhotoService(
            PhotoRepository repository,
            GcsSignedUrlService storage,
            TripMembershipPolicy memberships,
            EffectiveVisibilityService visibility,
            PhotoPolicyService policy,
            PhotoPlaceResolver placeResolver
        ) {
            return new PhotoService(
                repository,
                storage,
                memberships,
                visibility,
                policy,
                placeResolver
            );
        }

        @Bean
        PhotoReadService task10PhotoReadService(
            PhotoRepository repository,
            EffectiveVisibilityService visibility,
            GcsSignedUrlService storage
        ) {
            return new PhotoReadService(repository, visibility, storage);
        }

        @Bean
        PhotoController task10PhotoController(PhotoService photos) {
            return new PhotoController(photos);
        }

        @Bean
        PhotoReadController task10PhotoReadController(PhotoReadService photos) {
            return new PhotoReadController(photos);
        }

        @Bean
        TripPhotoController task10TripPhotoController(PhotoReadService photos) {
            return new TripPhotoController(photos);
        }
    }

    static final class HttpGcsSurface implements GcsObjectClient, AutoCloseable {
        private final HttpServer server;
        private final Map<String, ExpectedPut> expected = new HashMap<>();
        private final Map<String, ObjectMetadata> metadata = new HashMap<>();
        private final Map<String, byte[]> bytes = new HashMap<>();

        HttpGcsSurface() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload", this::upload);
            server.createContext("/read", this::read);
            server.start();
        }

        @Override
        public URI signPut(
            String objectKey,
            String contentType,
            String digest,
            Map<String, String> objectMetadata,
            Duration ttl
        ) {
            expected.put(objectKey, new ExpectedPut(contentType, digest, objectMetadata));
            return endpoint("upload", objectKey);
        }

        @Override
        public URI signRead(String objectKey, Duration ttl) {
            return endpoint("read", objectKey);
        }

        @Override
        public Optional<ObjectMetadata> find(String objectKey) {
            return Optional.ofNullable(metadata.get(objectKey));
        }

        @Override
        public ContentDigest digest(String objectKey, long maxBytes) {
            byte[] payload = bytes.get(objectKey);
            if (payload == null || payload.length > maxBytes) {
                throw new IllegalArgumentException("object content is unavailable or oversized");
            }
            return new ContentDigest(payload.length, uncheckedSha256(payload), crc32c(payload));
        }

        void remove(String key) {
            metadata.remove(key);
            bytes.remove(key);
        }

        void replaceMetadata(String key, String name, String value) {
            ObjectMetadata current = metadata.get(key);
            Map<String, String> changed = new HashMap<>(current.metadata());
            changed.put(name, value);
            metadata.put(key, new ObjectMetadata(
                current.objectKey(),
                current.contentType(),
                current.sizeBytes(),
                current.crc32c(),
                changed
            ));
        }

        int objectCount() {
            return metadata.size();
        }

        int signedPolicyCount() {
            return expected.size();
        }

        void clear() {
            expected.clear();
            metadata.clear();
            bytes.clear();
        }

        private void upload(HttpExchange exchange) throws IOException {
            String key = key(exchange);
            ExpectedPut policy = expected.get(key);
            byte[] payload = exchange.getRequestBody().readAllBytes();
            boolean valid = policy != null
                && "PUT".equals(exchange.getRequestMethod())
                && policy.contentType().equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                && policy.digest().equals(exchange.getRequestHeaders().getFirst("x-goog-content-sha256"))
                && policy.digest().equals(uncheckedSha256(payload));
            if (valid) {
                for (Map.Entry<String, String> entry : policy.metadata().entrySet()) {
                    valid &= entry.getValue().equals(exchange.getRequestHeaders().getFirst(
                        "x-goog-meta-" + entry.getKey()
                    ));
                }
            }
            if (valid) {
                bytes.put(key, payload);
                metadata.put(key, new ObjectMetadata(
                    key,
                    policy.contentType(),
                    payload.length,
                    crc32c(payload),
                    policy.metadata()
                ));
            }
            exchange.sendResponseHeaders(valid ? 200 : 400, -1);
            exchange.close();
        }

        private void read(HttpExchange exchange) throws IOException {
            byte[] payload = bytes.get(key(exchange));
            if (payload == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        }

        private URI endpoint(String operation, String objectKey) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/" + operation + "?key="
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8));
        }

        private String key(HttpExchange exchange) {
            return URLDecoder.decode(
                exchange.getRequestURI().getRawQuery().substring("key=".length()),
                StandardCharsets.UTF_8
            );
        }

        @Override
        public void close() {
            clear();
            server.stop(0);
        }

        private static String uncheckedSha256(byte[] payload) {
            try {
                return sha256(payload);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private static String crc32c(byte[] payload) {
            CRC32C crc = new CRC32C();
            crc.update(payload, 0, payload.length);
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array()
            );
        }

        record ExpectedPut(
            String contentType,
            String digest,
            Map<String, String> metadata
        ) {
            ExpectedPut {
                metadata = Map.copyOf(metadata);
            }
        }
    }
}
