package com.stog.backend.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class CellVisibilityTest {
    private static final Instant FIXED_TIME = Instant.parse("2040-01-01T00:00:00Z");
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;
    private static final long STRUCTURALLY_INVALID_H3 = 621496748577128448L;

    @Autowired
    private CellService cells;

    @Autowired
    private CellRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private long sequence;

    @Test
    void aggregatesLicensedLandmarkOwnVisitOwnPhotoAndEligibleTopPhotoIntoOneCell() {
        long viewerId = user("viewer");
        long publicOwnerId = user("public-owner");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long landmarkPlaceId = licensedLandmark(cellId, LATITUDE, LONGITUDE, "aggregate");
        addLandmarkImage(landmarkPlaceId);
        addLicensedSourceRecord(landmarkPlaceId, "aggregate-second-source");
        long snapshotPlaceId = snapshotPlace("Original snapshot place");
        long publicPhotoId = eligiblePublicSetLogPhoto(
            publicOwnerId, cellId, LATITUDE, LONGITUDE, snapshotPlaceId, 3, FIXED_TIME
        );
        long visitTripId = trip(viewerId, "private");
        visit(viewerId, visitTripId, cellId, LATITUDE, LONGITUDE);
        long ownPhotoId = privatePhoto(
            viewerId, visitTripId, cellId, LATITUDE, LONGITUDE, "aggregate-own", FIXED_TIME
        );
        cellStats(cellId, 1, 1, 3, publicPhotoId);

        CellResponses.Page page = cells.summaries(
            viewerId, 35.80, 127.10, 35.83, 127.20, null, null
        );
        CellResponses.Summary summary = page.items().get(0);
        CellResponses.Detail detail = cells.detail(viewerId, summary.cell_id());

        assertThat(summary.cell_id()).isEqualTo(CellIdCalculator.toWire(cellId));
        assertThat(summary.landmark_count()).isEqualTo(1L);
        assertThat(summary.landmark_name()).isEqualTo("task14 landmark aggregate");
        assertThat(summary.landmark_image_url()).isEqualTo(
            "https://example.com/task14-aggregate.jpg"
        );
        assertThat(summary.public_photo_count()).isEqualTo(1L);
        assertThat(summary.public_photo_like_count()).isEqualTo(3L);
        assertThat(summary.top_photo_id()).isEqualTo(publicPhotoId);
        assertThat(summary.my_visit_count()).isEqualTo(1L);
        assertThat(summary.my_photo_count()).isEqualTo(1L);
        assertThat(summary.background()).isEqualTo("hot");
        assertThat(summary.badges()).containsExactly("landmark");
        assertThat(detail.visibility_reasons()).containsExactly(
            "licensed_landmark",
            "eligible_public_photo",
            "my_visit",
            "my_photo"
        );
        assertThat(page.items()).extracting(CellResponses.Summary::cell_id).doesNotHaveDuplicates();

        CellResponses.PhotoPage photos = cells.photos(viewerId, summary.cell_id(), null, null);
        assertThat(photos.items()).extracting(CellResponses.Photo::id)
            .containsExactlyInAnyOrder(publicPhotoId, ownPhotoId);
        assertThat(photos.items()).allSatisfy(photo -> {
            assertThat(photo.cell_id()).isEqualTo(CellIdCalculator.toWire(cellId));
            assertThat(CellIdCalculator.fromCoords(photo.lat(), photo.lng())).isEqualTo(cellId);
        });
        CellResponses.Photo publicItem = photos.items().stream()
            .filter(photo -> photo.id() == publicPhotoId)
            .findFirst()
            .orElseThrow();
        assertThat(publicItem.accuracy_m()).isEqualTo(12.5);
        assertThat(publicItem.taken_at()).isEqualTo(FIXED_TIME);
        assertThat(publicItem.caption()).isEqualTo("Located Set Log note");
        assertThat(publicItem.place_id()).isEqualTo(snapshotPlaceId);
        assertThat(publicItem.place_name()).isEqualTo("Original snapshot place");
        assertThat(publicItem.place_resolution_status()).isEqualTo("matched");
        assertThat(publicItem.visibility()).isEqualTo("public");
        assertThat(publicItem.visibility_scope()).isEqualTo("public");
        assertThat(publicItem.moderation_status()).isEqualTo("approved");
        assertThat(publicItem.publication_status()).isEqualTo("public");

        jdbc.sql("UPDATE places SET name = 'Renamed place', catalog_status = 'quarantined' "
                + "WHERE id = :placeId")
            .param("placeId", snapshotPlaceId)
            .update();
        CellResponses.Photo afterDelete = cells.photos(
            viewerId, summary.cell_id(), null, null
        ).items().stream().filter(photo -> photo.id() == publicPhotoId).findFirst().orElseThrow();
        assertThat(afterDelete.place_id()).isNull();
        assertThat(afterDelete.place_name()).isEqualTo("Original snapshot place");
    }

    @Test
    void excludesStructurallyInvalidStoredH3FromSummaryDetailAndPhotoProjections() {
        long ownerId = user("invalid-h3-owner");
        long validCellId = CellIdCalculator.fromCoords(35.816, 127.151);
        licensedLandmark(STRUCTURALLY_INVALID_H3, LATITUDE, LONGITUDE, "invalidh3");
        licensedLandmark(validCellId, 35.816, 127.151, "validafterinvalid");
        long publicPhotoId = eligiblePublicPhoto(
            ownerId,
            STRUCTURALLY_INVALID_H3,
            LATITUDE,
            LONGITUDE,
            "invalid-h3-photo",
            0,
            FIXED_TIME
        );
        cellStats(STRUCTURALLY_INVALID_H3, 1, 1, 0, publicPhotoId);

        assertThat(CellIdCalculator.isValidCell(STRUCTURALLY_INVALID_H3)).isFalse();
        assertThat(repository.findSummaries(
            new CellBounds(35.80, 127.10, 35.83, 127.20),
            null,
            0L,
            20
        )).extracting(CellRepository.SummaryRow::cellId).containsExactly(validCellId);
        assertThat(repository.findDetail(STRUCTURALLY_INVALID_H3, null)).isEmpty();
        assertThat(repository.findPhotos(
            STRUCTURALLY_INVALID_H3,
            null,
            null,
            20
        )).isEmpty();
        assertThat(cells.summaries(
            null,
            35.80,
            127.10,
            35.83,
            127.20,
            null,
            20
        ).items()).extracting(CellResponses.Summary::cell_id)
            .containsExactly(CellIdCalculator.toWire(validCellId));
    }

    @Test
    void excludesOrdinaryPublicPlacesFromGuestCells() {
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long placeId = licensedLandmark(cellId, LATITUDE, LONGITUDE, "ordinary");
        jdbc.sql("""
                UPDATE places
                SET category = 'business', public_cell_eligible = FALSE
                WHERE id = :placeId
                """)
            .param("placeId", placeId)
            .update();

        CellResponses.Page page = cells.summaries(
            null, 35.80, 127.10, 35.90, 127.20, null, null
        );

        assertThat(page.items())
            .extracting(CellResponses.Summary::cell_id)
            .doesNotContain(CellIdCalculator.toWire(cellId));
    }

    @Test
    void exposesOnlyPublicReasonsToGuestsAndFailsClosedForPrivateOtherUserBlockedAndCoordinateLessRows() {
        long publicOwnerId = user("public-owner");
        long privateOwnerId = user("private-owner");
        long cellId = CellIdCalculator.fromCoords(LATITUDE, LONGITUDE);
        long privateCellId = CellIdCalculator.fromCoords(35.84, 127.18);
        long blockedCellId = CellIdCalculator.fromCoords(35.86, 127.18);
        long revokedCellId = CellIdCalculator.fromCoords(35.88, 127.18);
        licensedLandmark(cellId, LATITUDE, LONGITUDE, "guest");
        long publicPhotoId = eligiblePublicPhoto(
            publicOwnerId, cellId, LATITUDE, LONGITUDE, "guest-public", 0, FIXED_TIME
        );
        cellStats(cellId, 1, 1, 0, publicPhotoId);

        long privateTripId = trip(privateOwnerId, "private");
        visit(privateOwnerId, privateTripId, privateCellId, 35.84, 127.18);
        privatePhoto(privateOwnerId, privateTripId, privateCellId, 35.84, 127.18, "private-other", FIXED_TIME);
        long coordinateLessPhotoId = coordinateLessPhoto(
            privateOwnerId, privateTripId, "coordinate-less"
        );
        blockedPublicPhoto(publicOwnerId, blockedCellId, 35.86, 127.18, "blocked");
        revokedPublicPhoto(publicOwnerId, revokedCellId, 35.88, 127.18, "revoked");

        CellResponses.Page guestPage = cells.summaries(
            null, 35.80, 127.10, 35.90, 127.20, null, null
        );
        CellResponses.Summary guest = guestPage.items().stream()
            .filter(item -> item.cell_id().equals(CellIdCalculator.toWire(cellId)))
            .findFirst()
            .orElseThrow();
        CellResponses.Detail guestDetail = cells.detail(null, guest.cell_id());

        assertThat(guest.cell_id()).isEqualTo(CellIdCalculator.toWire(cellId));
        assertThat(guest.my_visit_count()).isZero();
        assertThat(guest.my_photo_count()).isZero();
        assertThat(guestDetail.visibility_reasons()).containsExactly(
            "licensed_landmark", "eligible_public_photo"
        );
        assertThat(guestPage.items()).extracting(CellResponses.Summary::cell_id)
            .doesNotContain(
                CellIdCalculator.toWire(privateCellId),
                CellIdCalculator.toWire(blockedCellId)
            );
        assertThat(guestPage.items()).extracting(CellResponses.Summary::cell_id)
            .contains(CellIdCalculator.toWire(revokedCellId));
        assertThat(cells.photos(null, guest.cell_id(), null, null).items())
            .extracting(CellResponses.Photo::id)
            .containsExactly(publicPhotoId)
            .doesNotContain(coordinateLessPhotoId);
        CellResponses.Photo revoked = cells.photos(
            publicOwnerId, CellIdCalculator.toWire(revokedCellId), null, null
        ).items().get(0);
        assertThat(revoked.publication_status()).isEqualTo("public");
        assertThat(revoked.moderation_status()).isEqualTo("approved");
        assertThatThrownBy(() -> cells.detail(
            publicOwnerId, CellIdCalculator.toWire(blockedCellId)
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void admitsOnlyActiveGroupMembersToOtherMembersGroupPhotoAndDoesNotCreateEmptyCells() {
        long groupOwnerId = user("group-owner");
        long groupMemberId = user("group-member");
        long outsiderId = user("outsider");
        long groupCellId = CellIdCalculator.fromCoords(35.83, 127.14);
        long groupTripId = trip(groupOwnerId, "group");
        activeMember(groupTripId, groupMemberId);
        long groupPhotoId = photo(
            groupTripId,
            groupOwnerId,
            groupCellId,
            35.83,
            127.14,
            "group-photo",
            "group",
            "pending",
            0,
            FIXED_TIME
        );

        CellResponses.Page memberPage = cells.summaries(
            groupMemberId, 35.80, 127.10, 35.85, 127.20, null, null
        );
        CellResponses.Detail memberDetail = cells.detail(
            groupMemberId, CellIdCalculator.toWire(groupCellId)
        );

        assertThat(memberPage.items()).extracting(CellResponses.Summary::cell_id)
            .containsExactly(CellIdCalculator.toWire(groupCellId));
        assertThat(memberDetail.visibility_reasons()).containsExactly("authorized_group_photo");
        assertThat(cells.photos(groupMemberId, CellIdCalculator.toWire(groupCellId), null, null).items())
            .extracting(CellResponses.Photo::id)
            .containsExactly(groupPhotoId);
        assertThat(cells.summaries(
            outsiderId, 35.80, 127.10, 35.85, 127.20, null, null
        ).items()).isEmpty();
        jdbc.sql("UPDATE trip_members SET left_at = :leftAt "
                + "WHERE trip_id = :tripId AND user_id = :userId")
            .param("leftAt", Timestamp.from(FIXED_TIME))
            .param("tripId", groupTripId)
            .param("userId", groupMemberId)
            .update();
        assertThat(cells.summaries(
            groupMemberId, 35.80, 127.10, 35.85, 127.20, null, null
        ).items()).isEmpty();

        long emptyCellId = CellIdCalculator.fromCoords(35.81, 127.11);
        assertThatThrownBy(() -> cells.detail(null, CellIdCalculator.toWire(emptyCellId)))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cell_stats WHERE cell_id = :cellId")
            .param("cellId", emptyCellId)
            .query(Long.class)
            .single()).isZero();
    }

    private long licensedLandmark(long cellId, double latitude, double longitude, String name) {
        long sourceId = jdbc.sql("""
                INSERT INTO catalog_sources (source_key, name, provider_type)
                VALUES (:sourceKey, :name, 'tour_api')
                RETURNING id
                """)
            .param("sourceKey", "task14-source-" + name + "-" + (++sequence))
            .param("name", "task14 source " + name)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql("""
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'task14 license', :reviewedAt, CURRENT_DATE - 1,
                    TRUE, CAST('[\"name\", \"image\"]' AS jsonb), :digest
                )
                RETURNING id
                """)
            .param("sourceId", sourceId)
            .param("reviewedAt", Timestamp.from(FIXED_TIME))
            .param("digest", "task14-license-" + name + "-" + sequence)
            .query(Long.class)
            .single();
        long placeId = jdbc.sql("""
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, normalized_name, compact_name,
                    public_cell_eligible
                )
                VALUES (:name, 'tourist_attraction', :lat, :lng, :cellId,
                    'public_data', :normalizedName, :compactName, TRUE)
                RETURNING id
                """)
            .param("name", "task14 landmark " + name)
            .param("lat", latitude)
            .param("lng", longitude)
            .param("cellId", cellId)
            .param("normalizedName", "task14 landmark " + name)
            .param("compactName", "task14landmark" + name)
            .query(Long.class)
            .single();
        jdbc.sql("""
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, imported_at
                )
                VALUES (:placeId, :sourceId, :licenseId, :externalId,
                    :sourceDigest, :sourceUpdatedAt, :importedAt)
                """)
            .param("placeId", placeId)
            .param("sourceId", sourceId)
            .param("licenseId", licenseId)
            .param("externalId", "task14-external-" + name + "-" + sequence)
            .param("sourceDigest", "task14-source-digest-" + name + "-" + sequence)
            .param("sourceUpdatedAt", Timestamp.from(FIXED_TIME))
            .param("importedAt", Timestamp.from(FIXED_TIME))
            .update();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return placeId;
    }

    private void addLandmarkImage(long placeId) {
        jdbc.sql("""
                INSERT INTO place_source_images (
                    place_source_record_id, source_image_id, source_url,
                    license_snapshot_id, source_digest, reusable
                )
                SELECT source_record.id, :sourceImageId, :sourceUrl,
                       source_record.license_snapshot_id, :sourceDigest, TRUE
                FROM place_source_records source_record
                WHERE source_record.place_id = :placeId
                ORDER BY source_record.id
                LIMIT 1
                """)
            .param("placeId", placeId)
            .param("sourceImageId", "task14-image-aggregate")
            .param("sourceUrl", "https://example.com/task14-aggregate.jpg")
            .param("sourceDigest", "task14-image-digest-aggregate")
            .update();
    }

    private void addLicensedSourceRecord(long placeId, String name) {
        long sourceId = jdbc.sql("""
                INSERT INTO catalog_sources (source_key, name, provider_type)
                VALUES (:sourceKey, :name, 'area_restaurant')
                RETURNING id
                """)
            .param("sourceKey", "task14-source-" + name + "-" + (++sequence))
            .param("name", "task14 source " + name)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql("""
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'task14 second license', :reviewedAt, CURRENT_DATE - 1,
                    TRUE, CAST('[\"name\"]' AS jsonb), :digest
                )
                RETURNING id
                """)
            .param("sourceId", sourceId)
            .param("reviewedAt", Timestamp.from(FIXED_TIME))
            .param("digest", "task14-license-" + name + "-" + sequence)
            .query(Long.class)
            .single();
        jdbc.sql("""
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, imported_at
                )
                VALUES (
                    :placeId, :sourceId, :licenseId, :externalId,
                    :sourceDigest, :sourceUpdatedAt, :importedAt
                )
                """)
            .param("placeId", placeId)
            .param("sourceId", sourceId)
            .param("licenseId", licenseId)
            .param("externalId", "task14-external-" + name + "-" + sequence)
            .param("sourceDigest", "task14-source-digest-" + name + "-" + sequence)
            .param("sourceUpdatedAt", Timestamp.from(FIXED_TIME))
            .param("importedAt", Timestamp.from(FIXED_TIME))
            .update();
    }

    private long eligiblePublicSetLogPhoto(
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        long placeId,
        long likes,
        Instant createdAt
    ) {
        long tripId = trip(ownerId, "public");
        long photoId = jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng,
                    location_accuracy_m, location_provenance, taken_at,
                    original_key, thumb_key, caption, place_id, place_name_snapshot,
                    place_resolution_status, visibility, moderation_status, public_consent,
                    like_count, created_at
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :cellId, :lat, :lng,
                    12.5, 'camera_foreground', :takenAt,
                    :originalKey, :thumbKey, 'Located Set Log note', :placeId,
                    'Original snapshot place', 'matched', 'public', 'approved', TRUE,
                    :likes, :createdAt
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("cellId", cellId)
            .param("lat", latitude)
            .param("lng", longitude)
            .param("takenAt", Timestamp.from(createdAt))
            .param("originalKey", "task14/set-log-" + (++sequence) + ".jpg")
            .param("thumbKey", "task14/set-log-thumb-" + sequence + ".jpg")
            .param("placeId", placeId)
            .param("likes", likes)
            .param("createdAt", Timestamp.from(createdAt))
            .query(Long.class)
            .single();
        grant(photoId, ownerId);
        return photoId;
    }

    private long eligiblePublicPhoto(
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        String name,
        long likes,
        Instant createdAt
    ) {
        long tripId = trip(ownerId, "public");
        long photoId = photo(
            tripId, ownerId, cellId, latitude, longitude, name, "public", "approved", likes, createdAt
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

    private void blockedPublicPhoto(
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        String name
    ) {
        long tripId = trip(ownerId, "public");
        long photoId = photo(
            tripId, ownerId, cellId, latitude, longitude, name, "public", "blocked", 0, FIXED_TIME
        );
        grant(photoId, ownerId);
    }

    private void revokedPublicPhoto(
        long ownerId,
        long cellId,
        double latitude,
        double longitude,
        String name
    ) {
        long tripId = trip(ownerId, "public");
        long photoId = photo(
            tripId, ownerId, cellId, latitude, longitude, name, "public", "approved", 0, FIXED_TIME
        );
        grant(photoId, ownerId);
        jdbc.sql("UPDATE photo_public_grants SET revoked_at = CURRENT_TIMESTAMP WHERE photo_id = :photoId")
            .param("photoId", photoId)
            .update();
    }

    private long privatePhoto(
        long ownerId,
        long tripId,
        long cellId,
        double latitude,
        double longitude,
        String name,
        Instant createdAt
    ) {
        return photo(
            tripId, ownerId, cellId, latitude, longitude, name, "private", "pending", 0, createdAt
        );
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
        long likes,
        Instant createdAt
    ) {
        return jdbc.sql("""
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng, original_key, thumb_key,
                    visibility, moderation_status, like_count, created_at
                )
                VALUES (
                    :tripId, :ownerId, 'camera', :cellId, :lat, :lng, :originalKey, :thumbKey,
                    :visibility, :moderationStatus, :likes, :createdAt
                )
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("cellId", cellId)
            .param("lat", latitude)
            .param("lng", longitude)
            .param("originalKey", "task14/" + name + "-" + (++sequence) + ".jpg")
            .param("thumbKey", "task14/" + name + "-thumb-" + sequence + ".jpg")
            .param("visibility", visibility)
            .param("moderationStatus", moderationStatus)
            .param("likes", likes)
            .param("createdAt", Timestamp.from(createdAt))
            .query(Long.class)
            .single();
    }

    private long coordinateLessPhoto(long ownerId, long tripId, String name) {
        return jdbc.sql("""
                INSERT INTO photos (trip_id, user_id, source, original_key, thumb_key, visibility,
                    caption, place_resolution_status)
                VALUES (:tripId, :ownerId, 'gallery', :originalKey, :thumbKey, 'private',
                    'Archive-only gallery note', 'no_match')
                RETURNING id
                """)
            .param("tripId", tripId)
            .param("ownerId", ownerId)
            .param("originalKey", "task14/" + name + "-" + (++sequence) + ".jpg")
            .param("thumbKey", "task14/" + name + "-thumb-" + sequence + ".jpg")
            .query(Long.class)
            .single();
    }

    private long snapshotPlace(String name) {
        return jdbc.sql("""
                INSERT INTO places (
                    name, source, normalized_name, compact_name, catalog_status
                )
                VALUES (:name, 'user', :normalizedName, :compactName, 'private_reference')
                RETURNING id
                """)
            .param("name", name)
            .param("normalizedName", name.toLowerCase(java.util.Locale.ROOT))
            .param("compactName", name.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))
            .query(Long.class)
            .single();
    }

    private void grant(long photoId, long ownerId) {
        jdbc.sql("""
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (:photoId, 1, :ownerId)
                """)
            .param("photoId", photoId)
            .param("ownerId", ownerId)
            .update();
    }

    private void cellStats(
        long cellId,
        long landmarkCount,
        long publicPhotoCount,
        long publicPhotoLikeCount,
        long topPhotoId
    ) {
        jdbc.sql("""
                INSERT INTO cell_stats (
                    cell_id, landmark_count, public_photo_count,
                    public_photo_like_count, top_photo_id
                )
                VALUES (:cellId, :landmarkCount, :photoCount, :likeCount, :topPhotoId)
                """)
            .param("cellId", cellId)
            .param("landmarkCount", landmarkCount)
            .param("photoCount", publicPhotoCount)
            .param("likeCount", publicPhotoLikeCount)
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
                    :userId, :tripId, gen_random_uuid(), repeat('c', 64),
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
                INSERT INTO trips (owner_id, title, activity_type, visibility, is_group)
                VALUES (:ownerId, :title, 'tour', :visibility, :isGroup)
                RETURNING id
                """)
            .param("ownerId", ownerId)
            .param("title", "task14 trip " + (++sequence))
            .param("visibility", visibility)
            .param("isGroup", "group".equals(visibility))
            .query(Long.class)
            .single();
    }

    private void activeMember(long tripId, long userId) {
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
    }

    private long user(String name) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", "task14-" + name + "-" + (++sequence))
            .query(Long.class)
            .single();
    }
}
