package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.storage.PhotoRequests;
import com.stog.backend.storage.PhotoRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PhotoSchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PhotoRepository photos;

    @Test
    void flywayCreatesCanonicalPhotoColumns() {
        List<String> columns = jdbc.sql(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'photos'
                ORDER BY ordinal_position
                """
            )
            .query(String.class)
            .list();

        assertThat(columns).containsExactly(
            "id",
            "trip_id",
            "user_id",
            "source",
            "cell_id",
            "lat",
            "lng",
            "taken_at",
            "original_key",
            "thumb_key",
            "caption",
            "like_count",
            "visibility",
            "created_at",
            "moderation_status",
            "finalize_trip_id",
            "client_upload_id",
            "finalize_fingerprint",
            "original_size_bytes",
            "original_sha256",
            "thumb_size_bytes",
            "thumb_sha256",
            "finalize_visibility",
            "finalize_moderation_status",
            "place_id",
            "place_name_snapshot",
            "place_resolution_status",
            "location_accuracy_m",
            "location_provenance",
            "public_consent",
            "publication_status",
            "finalize_place_id",
            "finalize_place_name_snapshot",
            "finalize_place_resolution_status",
            "finalize_publication_status"
        );
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '29' AND success"
            ).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '31' AND success"
            ).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name IN ('logs', 'set_logs')"
            ).query(Long.class).single()).isZero();
    }

    @Test
    void legacyCaptionLongerThanSetLogLimitRemainsCompatible() {
        long ownerId = user("legacy-long-caption-owner");
        long tripId = jdbc.sql(
                "INSERT INTO trips (owner_id, title, activity_type) "
                    + "VALUES (:ownerId, 'legacy caption', 'tour') RETURNING id"
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        long photoId = jdbc.sql(
                "INSERT INTO photos (trip_id, user_id, source, original_key, thumb_key, caption) "
                    + "VALUES (:tripId, :ownerId, 'gallery', :originalKey, :thumbKey, :caption) "
                    + "RETURNING id"
            )
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "legacy/" + ownerId + "/original.jpg")
            .param("thumbKey", "legacy/" + ownerId + "/thumb.jpg")
            .param("caption", "x".repeat(121))
            .query(Long.class)
            .single();

        assertThat(photos.find(photoId)).get()
            .extracting(PhotoRepository.StoredPhoto::caption)
            .isEqualTo("x".repeat(121));
    }

    @Test
    void finalizedMatchedPhotoRejectsPlaceIdRewrite() {
        long ownerId = user("immutable-place-owner");
        long tripId = jdbc.sql(
                "INSERT INTO trips (owner_id, title, activity_type) "
                    + "VALUES (:ownerId, 'immutable place', 'tour') RETURNING id"
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        long originalPlaceId = place("Original immutable place", "original immutable place");
        long replacementPlaceId = place("Replacement immutable place", "replacement immutable place");
        long photoId = jdbc.sql(
                """
                INSERT INTO photos (
                    trip_id, user_id, source, lat, lng, original_key, thumb_key,
                    finalize_trip_id, client_upload_id, finalize_fingerprint,
                    original_size_bytes, original_sha256, thumb_size_bytes, thumb_sha256,
                    finalize_visibility, finalize_moderation_status,
                    place_id, place_name_snapshot, place_resolution_status,
                    location_accuracy_m, location_provenance, public_consent, publication_status,
                    finalize_place_id, finalize_place_name_snapshot,
                    finalize_place_resolution_status, finalize_publication_status
                )
                VALUES (
                    :tripId, :ownerId, 'camera', 35.815, 127.15, :originalKey, :thumbKey,
                    :tripId, CAST(:uploadId AS uuid), repeat('a', 64),
                    128, repeat('b', 64), 64, repeat('c', 64),
                    'private', 'pending',
                    :placeId, 'Original immutable place', 'matched',
                    10.0, 'camera_foreground', FALSE, 'private',
                    :placeId, 'Original immutable place', 'matched', 'private'
                )
                RETURNING id
                """
            )
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "immutable-place/" + ownerId + "/original.jpg")
            .param("thumbKey", "immutable-place/" + ownerId + "/thumb.jpg")
            .param("uploadId", "30303030-3030-3030-3030-303030303030")
            .param("placeId", originalPlaceId)
            .query(Long.class)
            .single();

        assertThatThrownBy(() -> jdbc.sql(
                "UPDATE photos SET place_id = :replacementPlaceId WHERE id = :photoId"
            )
            .param("replacementPlaceId", replacementPlaceId)
            .param("photoId", photoId)
            .update())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .hasStackTraceContaining("photo finalize contract is immutable");
    }

    @Test
    void finalizedMatchedPhotoRestrictsReferencedPlaceDeletion() {
        long ownerId = user("referenced-place-owner");
        long tripId = jdbc.sql(
                "INSERT INTO trips (owner_id, title, activity_type) "
                    + "VALUES (:ownerId, 'referenced place', 'tour') RETURNING id"
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        long placeId = place("Referenced immutable place", "referenced immutable place");
        jdbc.sql(
                """
                INSERT INTO photos (
                    trip_id, user_id, source, lat, lng, original_key, thumb_key,
                    finalize_trip_id, client_upload_id, finalize_fingerprint,
                    original_size_bytes, original_sha256, thumb_size_bytes, thumb_sha256,
                    finalize_visibility, finalize_moderation_status,
                    place_id, place_name_snapshot, place_resolution_status,
                    location_accuracy_m, location_provenance, public_consent, publication_status,
                    finalize_place_id, finalize_place_name_snapshot,
                    finalize_place_resolution_status, finalize_publication_status
                )
                VALUES (
                    :tripId, :ownerId, 'camera', 35.815, 127.15, :originalKey, :thumbKey,
                    :tripId, CAST(:uploadId AS uuid), repeat('d', 64),
                    128, repeat('e', 64), 64, repeat('f', 64),
                    'private', 'pending',
                    :placeId, 'Referenced immutable place', 'matched',
                    10.0, 'camera_foreground', FALSE, 'private',
                    :placeId, 'Referenced immutable place', 'matched', 'private'
                )
                """
            )
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "referenced-place/" + ownerId + "/original.jpg")
            .param("thumbKey", "referenced-place/" + ownerId + "/thumb.jpg")
            .param("uploadId", "31313131-3131-3131-3131-313131313131")
            .param("placeId", placeId)
            .update();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM places WHERE id = :placeId")
            .param("placeId", placeId)
            .update())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .hasStackTraceContaining("photos_place_id_fkey");
    }

    @Test
    void photoReadsAreScopedToTripOwner() {
        long ownerId = user("photo-owner");
        long otherId = user("photo-other");
        long tripId = jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, 'photo trip', 'tour')
                RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        long photoId = jdbc.sql(
                """
                INSERT INTO photos (
                    trip_id, user_id, source, original_key, thumb_key
                )
                VALUES (
                    :tripId, :ownerId, 'camera',
                    'photos/1/original.jpg', 'photos/1/thumb.jpg'
                )
                RETURNING id
                """
            )
            .params(java.util.Map.of("tripId", tripId, "ownerId", ownerId))
            .query(Long.class)
            .single();

        assertThat(photos.findOwned(ownerId, photoId)).isPresent();
        assertThat(photos.findOwned(otherId, photoId)).isEmpty();
    }

    @Test
    void repositoryStoresCalculatedCoordinatesAndChangesVisibility() {
        long ownerId = user("photo-repository-owner");
        long tripId = jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (:ownerId, 'photo repository trip', 'tour')
                RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .query(Long.class)
            .single();
        double latitude = 35.815;
        double longitude = 127.15;
        long cellId = CellIdCalculator.fromCoords(latitude, longitude);
        String uploadId = "22222222-2222-2222-2222-222222222222";
        PhotoRequests.Create request = new PhotoRequests.Create(
            tripId,
            "gallery",
            uploadId,
            "photos/accounts/%d/trips/%d/members/%d/uploads/%s/original.jpg".formatted(
                ownerId, tripId, ownerId, uploadId
            ),
            "photos/accounts/%d/trips/%d/members/%d/uploads/%s/thumbnail.jpg".formatted(
                ownerId, tripId, ownerId, uploadId
            ),
            new PhotoRequests.UploadObject("image/jpeg", 128L, "a".repeat(64)),
            new PhotoRequests.UploadObject("image/jpeg", 64L, "b".repeat(64)),
            latitude,
            longitude,
            Instant.parse("2026-08-19T00:00:00Z"),
            "repository test"
        );

        photos.requireTripOwner(ownerId, tripId);
        PhotoRepository.StoredPhoto stored = photos.create(
            ownerId,
            request,
            cellId
        );

        assertThat(stored.cellId()).isEqualTo(cellId);
        assertThat(stored.visibility()).isEqualTo("private");
        assertThat(photos.updateVisibility(ownerId, stored.id(), "public"))
            .get()
            .extracting(PhotoRepository.StoredPhoto::visibility)
            .isEqualTo("public");
    }

    private long user(String nickname) {
        return jdbc.sql(
                "INSERT INTO users (nickname) VALUES (:nickname) RETURNING id"
            )
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long place(String name, String normalizedName) {
        return jdbc.sql(
                """
                INSERT INTO places (name, source, normalized_name, compact_name)
                VALUES (:name, 'user', :normalizedName, :compactName)
                RETURNING id
                """
            )
            .param("name", name)
            .param("normalizedName", normalizedName)
            .param("compactName", normalizedName.replace(" ", ""))
            .query(Long.class)
            .single();
    }
}
