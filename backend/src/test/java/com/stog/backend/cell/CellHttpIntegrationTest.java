package com.stog.backend.cell;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CellHttpIntegrationTest {
    @LocalServerPort
    private int port;

    private static final long STRUCTURALLY_INVALID_H3 = 621496748577128448L;
    private static final String STRUCTURALLY_INVALID_H3_WIRE = "8a0000000000000";

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private JdbcClient jdbc;

    @Test
    void malformedH3RemainsABadRequestAcrossTheRealErrorDispatch() throws Exception {
        HttpResponse<String> response = send("/cells/not-a-cell", null);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void structurallyInvalidStoredH3IsOmittedInsteadOfProducingHttp500() throws Exception {
        InvalidStoredCellFixture fixture = insertInvalidStoredCell();
        try {
            HttpResponse<String> summaries = send(
                "/cells?swLat=35.80&swLng=127.10&neLat=35.83&neLng=127.20&limit=20",
                null
            );
            HttpResponse<String> detail = send(
                "/cells/" + STRUCTURALLY_INVALID_H3_WIRE,
                null
            );

            assertThat(CellIdCalculator.isValidCell(STRUCTURALLY_INVALID_H3)).isFalse();
            assertThat(summaries.statusCode()).isEqualTo(200);
            assertThat(summaries.body()).doesNotContain(STRUCTURALLY_INVALID_H3_WIRE);
            assertThat(detail.statusCode()).isEqualTo(400);
        } finally {
            deleteInvalidStoredCell(fixture);
        }
    }

    @Test
    void invalidBearerCannotEnterTheOptionalViewerOverlaySurface() throws Exception {
        HttpResponse<String> response = send(
            "/cells?swLat=35.0&swLng=126.0&neLat=36.2&neLng=128.0&limit=1",
            "Bearer invalid-token"
        );

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void guestCanReadTheBoundedCellSurfaceWithoutCredentials() throws Exception {
        HttpResponse<String> response = send(
            "/cells?swLat=35.0&swLng=126.0&neLat=36.2&neLng=128.0&limit=1",
            null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"items\"");
    }

    private InvalidStoredCellFixture insertInvalidStoredCell() {
        String suffix = UUID.randomUUID().toString();
        long sourceId = jdbc.sql("""
                INSERT INTO catalog_sources (source_key, name, provider_type)
                VALUES (:sourceKey, 'invalid H3 HTTP source', 'tour_api')
                RETURNING id
                """)
            .param("sourceKey", "invalid-h3-http-" + suffix)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql("""
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'invalid H3 HTTP license', :reviewedAt, CURRENT_DATE - 1,
                    TRUE, CAST('[\"name\"]' AS jsonb), :digest
                )
                RETURNING id
                """)
            .param("sourceId", sourceId)
            .param("reviewedAt", Timestamp.from(Instant.parse("2040-01-01T00:00:00Z")))
            .param("digest", "invalid-h3-http-license-" + suffix)
            .query(Long.class)
            .single();
        long placeId = jdbc.sql("""
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, external_id,
                    normalized_name, compact_name, public_cell_eligible
                )
                VALUES (
                    'Invalid H3 HTTP Place', 'ATTRACTION', 35.815, 127.15, :cellId,
                    'public_data', :externalId, 'invalid h3 http place',
                    'invalidh3httpplace', TRUE
                )
                RETURNING id
                """)
            .param("cellId", STRUCTURALLY_INVALID_H3)
            .param("externalId", "invalid-h3-http-place-" + suffix)
            .query(Long.class)
            .single();
        jdbc.sql("""
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, imported_at
                )
                VALUES (
                    :placeId, :sourceId, :licenseId, :externalId,
                    :sourceDigest, :recordedAt, :recordedAt
                )
                """)
            .param("placeId", placeId)
            .param("sourceId", sourceId)
            .param("licenseId", licenseId)
            .param("externalId", "invalid-h3-http-record-" + suffix)
            .param("sourceDigest", "invalid-h3-http-record-digest-" + suffix)
            .param("recordedAt", Timestamp.from(Instant.parse("2040-01-01T00:00:00Z")))
            .update();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return new InvalidStoredCellFixture(placeId, licenseId, sourceId);
    }

    private void deleteInvalidStoredCell(InvalidStoredCellFixture fixture) {
        jdbc.sql("DELETE FROM places WHERE id = :id")
            .param("id", fixture.placeId())
            .update();
        jdbc.sql("DELETE FROM license_snapshots WHERE id = :id")
            .param("id", fixture.licenseId())
            .update();
        jdbc.sql("DELETE FROM catalog_sources WHERE id = :id")
            .param("id", fixture.sourceId())
            .update();
    }

    private HttpResponse<String> send(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + path)
        ).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record InvalidStoredCellFixture(long placeId, long licenseId, long sourceId) {
    }
}
