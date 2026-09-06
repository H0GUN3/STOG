package com.stog.backend.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import com.uber.h3core.H3Core;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(CellControllerTest.SignedReadTestConfiguration.class)
class CellControllerTest {
    private static final Instant FIXED_TIME = Instant.parse("2041-01-01T00:00:00Z");
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private long sequence;

    @Test
    void servesOptionalAuthSummaryAndDetailWithOnlyViewerScopedOverlays() throws Exception {
        long viewerId = user("viewer");
        long publicOwnerId = user("public-owner");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long publicPhotoId = publicPhoto(
            publicOwnerId, cellId, LATITUDE, LONGITUDE, "public", FIXED_TIME, 2
        );
        long viewerTripId = trip(viewerId, "private");
        visit(viewerId, viewerTripId, cellId, LATITUDE, LONGITUDE);
        photo(
            viewerTripId, viewerId, cellId, LATITUDE, LONGITUDE,
            "viewer-private", "private", "pending", FIXED_TIME, 0
        );
        stats(cellId, 0, 1, 2, publicPhotoId);

        JsonNode guest = response(mockMvc.perform(cellsRequest())
            .andExpect(status().isOk())
            .andReturn());
        JsonNode viewer = response(mockMvc.perform(cellsRequest()
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andReturn());
        String cellIdWire = CellIdCalculator.toWire(cellId);
        JsonNode detail = response(mockMvc.perform(get("/cells/{cellId}", cellIdWire))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(guest.path("items")).hasSize(1);
        assertThat(guest.at("/items/0/cell_id").isTextual()).isTrue();
        assertThat(guest.at("/items/0/cell_id").asText()).isEqualTo(cellIdWire);
        assertThat(guest.at("/items/0/public_photo_count").asLong()).isEqualTo(1L);
        assertThat(guest.at("/items/0/my_visit_count").asLong()).isZero();
        assertThat(guest.at("/items/0/my_photo_count").asLong()).isZero();
        assertThat(guest.at("/items/0/centroid/lat").asDouble()).isCloseTo(
            LATITUDE, org.assertj.core.data.Offset.offset(0.01)
        );
        assertThat(guest.at("/items/0/centroid/lng").asDouble()).isCloseTo(
            LONGITUDE, org.assertj.core.data.Offset.offset(0.01)
        );
        assertThat(guest.at("/items/0/boundary").size()).isEqualTo(6);
        assertThat(detail.at("/boundary").size()).isEqualTo(6);
        assertThat(detail.at("/centroid/lat").asDouble()).isEqualTo(
            guest.at("/items/0/centroid/lat").asDouble()
        );
        assertThat(viewer.at("/items/0/my_visit_count").asLong()).isEqualTo(1L);
        assertThat(viewer.at("/items/0/my_photo_count").asLong()).isEqualTo(1L);
        assertThat(detail.path("visibility_reasons"))
            .extracting(JsonNode::asText)
            .containsExactly("eligible_public_photo");
    }

    @Test
    void rejectsMissingMalformedOrOutOfRangeBoundsAndH3Values() throws Exception {
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        String cellIdWire = CellIdCalculator.toWire(cellId);
        long resolutionNine = h3().latLngToCell(LATITUDE, LONGITUDE, 9);

        mockMvc.perform(get("/cells"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells")
                .param("swLat", "91").param("swLng", "127.10")
                .param("neLat", "35.83").param("neLng", "127.20"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells")
                .param("swLat", "35.80").param("swLng", "127.2")
                .param("neLat", "35.83").param("neLng", "127.1"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells")
                .param("swLat", "35.8").param("swLng", "127.10")
                .param("neLat", "35.8").param("neLng", "127.20"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells/{cellId}", "not-a-cell"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells/{cellId}", cellIdWire.toUpperCase()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells/{cellId}", h3().h3ToString(resolutionNine)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/cells/{cellId}", cellIdWire))
            .andExpect(status().isNotFound());
        mockMvc.perform(cellsRequest().param("limit", "0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(cellsRequest().param("limit", "21"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(cellsRequest().param("cursor", "not-a-cursor"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidBearerCredentialsCannotRequestViewerOverlays() throws Exception {
        long ownerId = user("unauthorized-overlay-owner");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long photoId = publicPhoto(
            ownerId, cellId, LATITUDE, LONGITUDE, "unauthorized-public", FIXED_TIME, 0
        );
        stats(cellId, 0, 1, 0, photoId);

        mockMvc.perform(cellsRequest().header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(cellsRequest())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].my_visit_count").value(0))
            .andExpect(jsonPath("$.items[0].my_photo_count").value(0));
    }

    @Test
    void returnsLatestOwnThumbnailAndMineOnlySignedPhotoPage() throws Exception {
        long viewerId = user("thumbnail-viewer");
        long otherOwnerId = user("thumbnail-other");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long otherPhotoId = publicPhoto(
            otherOwnerId, cellId, LATITUDE, LONGITUDE, "other", FIXED_TIME, 0
        );
        long viewerTripId = trip(viewerId, "private");
        long oldPhotoId = photo(
            viewerTripId, viewerId, cellId, LATITUDE, LONGITUDE,
            "old-viewer", "private", "pending", FIXED_TIME, 0
        );
        long latestPhotoId = photo(
            viewerTripId, viewerId, cellId, LATITUDE, LONGITUDE,
            "latest-viewer", "private", "pending", FIXED_TIME.plusSeconds(1), 0
        );

        JsonNode summary = response(mockMvc.perform(cellsRequest()
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andReturn())
            .at("/items/0");
        JsonNode mine = response(mockMvc.perform(get(
                "/cells/{cellId}/photos", CellIdCalculator.toWire(cellId)
            )
                .param("scope", "mine")
                .with(jwt().jwt(token -> token.subject(Long.toString(viewerId)))))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(summary.path("my_latest_photo_thumbnail_url").asText())
            .contains("latest-viewer-thumb");
        assertThat(mine.path("items")).hasSize(2);
        assertThat(mine.at("/items/0/id").asLong()).isEqualTo(latestPhotoId);
        assertThat(mine.at("/items/1/id").asLong()).isEqualTo(oldPhotoId);
        assertThat(mine.at("/items/0/thumbnail_url").asText())
            .contains("latest-viewer-thumb");
        assertThat(mine.path("items")).extracting(JsonNode::asText)
            .doesNotContain(Long.toString(otherPhotoId));
    }

    @Test
    void rejectsMineOnlyCellPhotosWithoutAuthentication() throws Exception {
        long ownerId = user("mine-unauthenticated");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        publicPhoto(ownerId, cellId, LATITUDE, LONGITUDE, "mine-unauthenticated", FIXED_TIME, 0);

        mockMvc.perform(get("/cells/{cellId}/photos", CellIdCalculator.toWire(cellId))
                .param("scope", "mine"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void paginatesSparseCellSummariesWithoutDuplicateOrSkip() throws Exception {
        long ownerId = user("summary-owner");
        long firstCellId = CellIdCalculator.fromCoords(35.81, 127.11);
        long secondCellId = CellIdCalculator.fromCoords(35.85, 127.15);
        long thirdCellId = CellIdCalculator.fromCoords(35.89, 127.19);
        publicPhoto(ownerId, firstCellId, 35.81, 127.11, "summary-first", FIXED_TIME, 0);
        publicPhoto(ownerId, secondCellId, 35.85, 127.15, "summary-second", FIXED_TIME, 0);
        publicPhoto(ownerId, thirdCellId, 35.89, 127.19, "summary-third", FIXED_TIME, 0);

        JsonNode firstPage = response(mockMvc.perform(summaryRequest(null))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode secondPage = response(mockMvc.perform(summaryRequest(
                firstPage.path("next_cursor").asText()
            ))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode thirdPage = response(mockMvc.perform(summaryRequest(
                secondPage.path("next_cursor").asText()
            ))
            .andExpect(status().isOk())
            .andReturn());

        List<String> cellIds = List.of(
            firstPage.at("/items/0/cell_id").asText(),
            secondPage.at("/items/0/cell_id").asText(),
            thirdPage.at("/items/0/cell_id").asText()
        );
        assertThat(cellIds).containsExactlyInAnyOrder(
            CellIdCalculator.toWire(firstCellId),
            CellIdCalculator.toWire(secondCellId),
            CellIdCalculator.toWire(thirdCellId)
        );
        assertThat(cellIds).doesNotHaveDuplicates();
        assertThat(thirdPage.path("next_cursor").isNull()).isTrue();
    }

    @Test
    void paginatesCellPhotosWithStableCreatedAtAndIdOrderingWithoutDuplicateOrSkip() throws Exception {
        long ownerId = user("page-owner");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long first = publicPhoto(ownerId, cellId, LATITUDE, LONGITUDE, "first", FIXED_TIME, 0);
        long second = publicPhoto(ownerId, cellId, LATITUDE, LONGITUDE, "second", FIXED_TIME, 0);
        long third = publicPhoto(ownerId, cellId, LATITUDE, LONGITUDE, "third", FIXED_TIME, 0);
        stats(cellId, 0, 3, 0, first);
        String cellIdWire = CellIdCalculator.toWire(cellId);

        JsonNode detail = response(mockMvc.perform(get("/cells/{cellId}", cellIdWire))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode firstPage = response(mockMvc.perform(get("/cells/{cellId}/photos", cellIdWire)
                .param("limit", "2"))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode secondPage = response(mockMvc.perform(get("/cells/{cellId}/photos", cellIdWire)
                .param("limit", "2")
                .param("cursor", firstPage.path("next_cursor").asText()))
            .andExpect(status().isOk())
            .andReturn());

        List<Long> ids = new ArrayList<>();
        firstPage.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        secondPage.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        assertThat(detail.path("cell_id").asText()).isEqualTo(cellIdWire);
        assertThat(detail.path("visibility_reasons"))
            .extracting(JsonNode::asText)
            .containsExactly("eligible_public_photo");
        assertThat(ids).containsExactly(third, second, first);
        assertThat(ids).doesNotHaveDuplicates();
        JsonNode exact = firstPage.at("/items/0");
        assertThat(exact.path("accuracy_m").asDouble()).isEqualTo(10.0);
        assertThat(exact.path("caption").asText()).startsWith("Cell note ");
        assertThat(exact.path("place_id").isNull()).isTrue();
        assertThat(exact.path("place_name").isNull()).isTrue();
        assertThat(exact.path("place_resolution_status").asText()).isEqualTo("no_match");
        assertThat(exact.path("visibility").asText()).isEqualTo("public");
        assertThat(exact.path("visibility_scope").asText()).isEqualTo("public");
        assertThat(exact.path("moderation_status").asText()).isEqualTo("approved");
        assertThat(exact.path("publication_status").asText()).isEqualTo("public");
        assertThat(firstPage.path("next_cursor").asText()).isNotBlank();
        assertThat(secondPage.path("next_cursor").isNull()).isTrue();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cellsRequest() {
        return get("/cells")
            .param("swLat", "35.80")
            .param("swLng", "127.10")
            .param("neLat", "35.83")
            .param("neLng", "127.20");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summaryRequest(
        String cursor
    ) {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = get("/cells")
            .param("swLat", "35.80")
            .param("swLng", "127.10")
            .param("neLat", "35.90")
            .param("neLng", "127.20")
            .param("limit", "1");
        return cursor == null ? request : request.param("cursor", cursor);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private long publicPhoto(
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        String name,
        Instant createdAt,
        long likes
    ) {
        long tripId = trip(ownerId, "public");
        long photoId = photo(
            tripId, ownerId, cellId, latitude, longitude, name, "public", "approved", createdAt, likes
        );
        jdbc.sql("""
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (:photoId, 1, :ownerId)
                """)
            .param("photoId", photoId)
            .param("ownerId", ownerId)
            .update();
        return photoId;
    }

    private long photo(
        long tripId,
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        String name,
        String visibility,
        String moderationStatus,
        Instant createdAt,
        long likes
    ) {
        return jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng,
                    location_accuracy_m, location_provenance, taken_at,
                    original_key, thumb_key, caption, place_resolution_status,
                    visibility, moderation_status, public_consent, created_at, like_count
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :cellId, :lat, :lng,
                    10.0, 'camera_foreground', :createdAt,
                    :originalKey, :thumbKey, :caption, 'no_match',
                    :visibility, :moderationStatus, TRUE, :createdAt, :likes
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("cellId", cellId)
            .param("lat", latitude)
            .param("lng", longitude)
            .param("originalKey", "task14-controller/" + name + "-" + (++sequence) + ".jpg")
            .param("thumbKey", "task14-controller/" + name + "-thumb-" + sequence + ".jpg")
            .param("caption", "Cell note " + name)
            .param("visibility", visibility)
            .param("moderationStatus", moderationStatus)
            .param("createdAt", Timestamp.from(createdAt))
            .param("likes", likes)
            .query(Long.class)
            .single();
    }

    private void stats(long cellId, long landmarks, long photos, long likes, long topPhotoId) {
        jdbc.sql("""
                INSERT INTO cell_stats (
                    cell_id, landmark_count, public_photo_count,
                    public_photo_like_count, top_photo_id
                )
                VALUES (:cellId, :landmarks, :photos, :likes, :topPhotoId)
                """)
            .param("cellId", cellId)
            .param("landmarks", landmarks)
            .param("photos", photos)
            .param("likes", likes)
            .param("topPhotoId", topPhotoId)
            .update();
    }

    private void visit(long userId, long tripId, long cellId, double latitude, double longitude) {
        jdbc.sql("""
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    :userId, :tripId, gen_random_uuid(), repeat('d', 64),
                    :cellId, :lat, :lng, :enteredAt, :leftAt, 'visited', FALSE
                )
                """)
            .param("userId", userId)
            .param("tripId", tripId)
            .param("cellId", cellId)
            .param("lat", latitude)
            .param("lng", longitude)
            .param("enteredAt", Timestamp.from(FIXED_TIME))
            .param("leftAt", Timestamp.from(FIXED_TIME.plusSeconds(1)))
            .update();
    }

    private long trip(long ownerId, String visibility) {
        return jdbc.sql("""
                INSERT INTO trips (owner_id, title, activity_type, visibility)
                VALUES (:ownerId, :title, 'tour', :visibility)
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", "task14 controller trip " + (++sequence))
            .param("visibility", visibility)
            .query(Long.class)
            .single();
    }

    private long user(String name) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "task14-controller-" + name + "-" + (++sequence))
            .query(Long.class)
            .single();
    }

    private H3Core h3() {
        try {
            return H3Core.newInstance();
        } catch (Exception error) {
            throw new AssertionError("H3 native library could not load", error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedReadTestConfiguration {
        @Bean
        GcsReadUrlSigner signedReadUrlSigner() {
            return objectKey -> URI.create("https://storage.test/read/" + objectKey);
        }
    }
}
