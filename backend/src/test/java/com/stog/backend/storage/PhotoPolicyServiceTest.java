package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.plan.EffectiveVisibilityService;
import com.stog.backend.plan.TripMembershipPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class PhotoPolicyServiceTest {
    private static final Instant TAKEN_AT = Instant.parse("2026-08-20T12:00:00Z");

    @Autowired
    private PhotoRepository repository;

    @Autowired
    private TripMembershipPolicy memberships;

    @Autowired
    private EffectiveVisibilityService visibility;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PhotoPlaceResolver placeResolver;

    private FixedGcsObjectClient objects;
    private GcsSignedUrlService storage;
    private PhotoService photos;
    private long uploadSequence;

    @BeforeEach
    void setUp() {
        objects = new FixedGcsObjectClient();
        storage = new GcsSignedUrlService(
            objects,
            new StorageProperties("stog-test-media", Duration.ofMinutes(5), "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
            Clock.fixed(TAKEN_AT, ZoneOffset.UTC)
        );
        photos = new PhotoService(
            repository,
            storage,
            memberships,
            visibility,
            new PhotoPolicyService(repository),
            placeResolver
        );
    }

    @Test
    void finalizationSnapshotsPlaceAndCreatesOneGrantWithTruthfulPublicationStatus() {
        long ownerId = user("set-log-owner");
        long privateTripId = trip(ownerId, "private");
        long placeId = canonicalPlace("Original canonical name", 35.815, 127.15);
        PhotoRequests.Create privateIntent = setLogRequest(
            request(ownerId, privateTripId, "camera", 35.815, 127.15),
            placeId,
            "public",
            true
        );

        PhotoResponses.Detail created = create(ownerId, privateIntent);

        assertThat(created.place_id()).isEqualTo(placeId);
        assertThat(created.place_name()).isEqualTo("Original canonical name");
        assertThat(created.cell_id()).isEqualTo(CellIdCalculator.toWire(
            CellIdCalculator.fromCoords(35.815, 127.15)
        ));
        assertThat(created.visibility()).isEqualTo("public");
        assertThat(created.moderation_status()).isEqualTo("pending");
        assertThat(created.publication_status()).isEqualTo("trip_not_public");
        assertThat(activeGrantCount(created.id())).isEqualTo(1L);

        jdbc.sql("UPDATE places SET name = 'Renamed catalog place', "
                + "catalog_status = 'quarantined' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        PhotoResponses.Detail replay = photos.create(ownerId, privateIntent);
        assertThat(replay.place_id()).isEqualTo(placeId);
        assertThat(replay.place_name()).isEqualTo("Original canonical name");
        assertThat(replay.publication_status()).isEqualTo("trip_not_public");
        assertThat(activeGrantCount(created.id())).isEqualTo(1L);

        long publicTripId = trip(ownerId, "public");
        long publicPlaceId = canonicalPlace("Public trip place", 35.82, 127.16);
        PhotoResponses.Detail publicTripPhoto = create(ownerId, setLogRequest(
            request(ownerId, publicTripId, "camera", 35.82, 127.16),
            publicPlaceId,
            "public",
            true
        ));
        assertThat(publicTripPhoto.publication_status()).isEqualTo("public");
        assertThat(activeGrantCount(publicTripPhoto.id())).isEqualTo(1L);
    }

    @Test
    void createsMetadataOnlyAfterBothNormalizedObjectsValidate() {
        long ownerId = user("metadata-owner");
        long tripId = trip(ownerId, "private");
        PhotoRequests.Create request = request(ownerId, tripId, "camera", 35.815, 127.15);

        upload(request);
        objects.remove(request.thumb_key());
        assertBadRequest(() -> photos.create(ownerId, request));
        assertThat(photoCount(request.original_key())).isZero();

        upload(request);
        PhotoResponses.Detail created = photos.create(ownerId, request);

        assertThat(created.id()).isPositive();
        assertThat(photoCount(request.original_key())).isEqualTo(1L);
    }

    @Test
    void duplicateFinalizeReturnsTheOriginalPhotoWithoutCreatingAnotherRow() {
        long ownerId = user("duplicate-finalize-owner");
        long tripId = trip(ownerId, "private");
        PhotoRequests.Create request = request(ownerId, tripId, "camera", 35.815, 127.15);
        upload(request);

        PhotoResponses.Detail first = photos.create(ownerId, request);
        PhotoResponses.Detail replay = photos.create(ownerId, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(photoCount(request.original_key())).isEqualTo(1L);
    }

    @Test
    void rejectsMismatchedAndForeignUploadedObjectMetadataBeforePersistence() {
        long ownerId = user("metadata-mismatch-owner");
        long tripId = trip(ownerId, "private");
        PhotoRequests.Create mismatch = request(ownerId, tripId, "camera", 35.815, 127.15);
        authorizeUpload(mismatch);
        objects.put(mismatch.original_key(), "image/jpeg", "normalized-original");
        objects.put(mismatch.thumb_key(), "image/png", "thumbnail");

        assertBadRequest(() -> photos.create(ownerId, mismatch));
        assertThat(photoCount(mismatch.original_key())).isZero();

        PhotoRequests.Create foreign = request(ownerId, tripId, "camera", 35.816, 127.151);
        authorizeUpload(foreign);
        objects.put(
            foreign.original_key(),
            "photos/999/00000000-0000-0000-0000-000000000999.jpg",
            "image/jpeg",
            128L,
            "normalized-original"
        );
        objects.put(foreign.thumb_key(), "image/jpeg", "thumbnail");

        assertBadRequest(() -> photos.create(ownerId, foreign));
        assertThat(photoCount(foreign.original_key())).isZero();
    }

    @Test
    void calculatesServerH3AndKeepsCoordinateLessGalleryPhotosInTheArchive() {
        long ownerId = user("archive-owner");
        long tripId = trip(ownerId, "private");
        PhotoRequests.Create located = request(ownerId, tripId, "camera", 35.815, 127.15);
        PhotoRequests.Create coordinateLess = request(ownerId, tripId, "gallery", null, null);

        PhotoResponses.Detail locatedPhoto = create(ownerId, located);
        PhotoResponses.Detail galleryPhoto = create(ownerId, coordinateLess);
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);

        assertThat(locatedPhoto.cell_id()).isEqualTo(CellIdCalculator.toWire(cellId));
        assertThat(galleryPhoto.cell_id()).isNull();
        assertThat(jdbc.sql("SELECT cell_id IS NULL FROM photos WHERE id = :photoId")
            .param("photoId", galleryPhoto.id())
            .query(Boolean.class)
            .single()).isTrue();
        assertThat(photos.archive(ownerId, tripId))
            .extracting(PhotoResponses.ArchiveItem::id)
            .contains(locatedPhoto.id(), galleryPhoto.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cell_stats WHERE cell_id = :cellId")
            .param("cellId", cellId)
            .query(Long.class)
            .single()).isZero();
    }

    @Test
    void activeMembersCanCreateAndReadGroupPhotosButCannotChangeAnotherOwnersPhoto() {
        long ownerId = user("group-owner");
        long memberId = user("group-member");
        long outsiderId = user("group-outsider");
        long tripId = trip(ownerId, "group");
        activeMember(tripId, memberId);
        PhotoResponses.Detail created = create(
            memberId,
            request(memberId, tripId, "camera", 35.815, 127.15)
        );

        photos.changeVisibility(
            memberId,
            created.id(),
            new PhotoRequests.Visibility("group")
        );

        assertThat(photos.get(memberId, created.id()).id()).isEqualTo(created.id());
        assertThat(photos.get(ownerId, created.id()).id()).isEqualTo(created.id());
        assertForbidden(() -> photos.get(outsiderId, created.id()));
        assertForbidden(() -> photos.changeVisibility(
            ownerId,
            created.id(),
            new PhotoRequests.Visibility("private")
        ));
    }

    @Test
    void publicReadsNeedPublicTripAndPhotoVisibilityOnly() {
        long ownerId = user("public-owner");
        long outsiderId = user("public-outsider");
        long moderatorId = moderator("public-moderator");
        long tripId = trip(ownerId, "public");
        PhotoResponses.Detail created = create(
            ownerId,
            request(ownerId, tripId, "camera", 35.815, 127.15)
        );
        photos.changeVisibility(ownerId, created.id(), new PhotoRequests.Visibility("public"));

        assertThat(photos.get(ownerId, created.id()).id()).isEqualTo(created.id());
        objects.clearSignedReads();
        assertThat(photos.get(outsiderId, created.id()).id()).isEqualTo(created.id());

        photos.acceptPublicGrant(ownerId, created.id());
        assertThat(photos.get(outsiderId, created.id()).id()).isEqualTo(created.id());

        photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("approved", "public review passed")
        );

        assertThat(photos.get(outsiderId, created.id()).id()).isEqualTo(created.id());
    }

    @Test
    void ownerGrantAuditDoesNotControlPublicEligibilityOrProjection() {
        long ownerId = user("grant-owner");
        long outsiderId = user("grant-outsider");
        long moderatorId = moderator("grant-moderator");
        long tripId = trip(ownerId, "public");
        PhotoResponses.Detail created = create(
            ownerId,
            request(ownerId, tripId, "camera", 35.815, 127.15)
        );
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        photos.changeVisibility(ownerId, created.id(), new PhotoRequests.Visibility("public"));
        PhotoResponses.PublicGrant first = photos.acceptPublicGrant(ownerId, created.id());
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.scope()).containsExactly("feed", "like", "cell_featured", "signed_read");
        assertConflict(() -> photos.acceptPublicGrant(ownerId, created.id()));
        assertForbidden(() -> photos.revokePublicGrant(outsiderId, created.id(), first.version()));

        photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("approved", "approved")
        );
        assertThat(isPubliclyEligible(created.id())).isTrue();
        assertProjection(cellId, created.id());

        PhotoResponses.PublicGrant revoked = photos.revokePublicGrant(
            ownerId,
            created.id(),
            first.version()
        );
        assertThat(revoked.revoked_at()).isNotNull();
        assertThat(isPubliclyEligible(created.id())).isTrue();
        assertProjection(cellId, created.id());
        assertThat(photos.get(outsiderId, created.id()).id()).isEqualTo(created.id());

        PhotoResponses.PublicGrant second = photos.acceptPublicGrant(ownerId, created.id());
        assertThat(second.version()).isEqualTo(2);
        assertProjection(cellId, created.id());
    }

    @Test
    void moderatorRecordsAppendOnlyActorAndRejectsInvalidTransitions() {
        long ownerId = user("moderation-owner");
        long nonModeratorId = user("not-moderator");
        long moderatorId = moderator("moderation-moderator");
        long tripId = trip(ownerId, "private");
        PhotoResponses.Detail created = create(
            ownerId,
            request(ownerId, tripId, "camera", 35.815, 127.15)
        );

        assertForbidden(() -> photos.moderate(
            nonModeratorId,
            created.id(),
            new PhotoRequests.Moderation("approved", "not allowed")
        ));

        PhotoResponses.Moderation approved = photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("approved", "reviewed by moderator")
        );

        assertThat(approved.photo_id()).isEqualTo(created.id());
        assertThat(approved.moderator_id()).isEqualTo(moderatorId);
        assertThat(approved.from_status()).isEqualTo("pending");
        assertThat(approved.to_status()).isEqualTo("approved");
        assertThat(jdbc.sql(
                """
                SELECT moderator_id || '|' || from_status || '|' || to_status || '|' || reason
                FROM photo_moderation_events
                WHERE photo_id = :photoId
                """
            )
            .param("photoId", created.id())
            .query(String.class)
            .single()).isEqualTo(
                moderatorId + "|pending|approved|reviewed by moderator"
            );
        assertConflict(() -> photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("blocked", "invalid second transition")
        ));
        assertBadRequest(() -> photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("pending", "invalid target")
        ));
    }

    @Test
    void blockingRefreshesAStaleProjectionAndLeavesThePhotoIneligible() {
        long ownerId = user("blocked-owner");
        long moderatorId = moderator("blocked-moderator");
        long tripId = trip(ownerId, "public");
        PhotoResponses.Detail created = create(
            ownerId,
            request(ownerId, tripId, "camera", 35.815, 127.15)
        );
        long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
        photos.changeVisibility(ownerId, created.id(), new PhotoRequests.Visibility("public"));
        photos.acceptPublicGrant(ownerId, created.id());
        jdbc.sql(
                """
                UPDATE cell_stats
                SET public_photo_count = 1,
                    public_photo_like_count = 0,
                    top_photo_id = :photoId
                WHERE cell_id = :cellId
                """
            )
            .param("cellId", cellId)
            .param("photoId", created.id())
            .update();

        PhotoResponses.Moderation blocked = photos.moderate(
            moderatorId,
            created.id(),
            new PhotoRequests.Moderation("blocked", "policy violation")
        );

        assertThat(blocked.to_status()).isEqualTo("blocked");
        assertThat(isPubliclyEligible(created.id())).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM cell_stats WHERE cell_id = :cellId")
            .param("cellId", cellId)
            .query(Long.class)
            .single()).isZero();
    }

    private PhotoRequests.Create setLogRequest(
        PhotoRequests.Create source,
        long placeId,
        String visibility,
        boolean consent
    ) {
        return new PhotoRequests.Create(
            source.trip_id(), source.source(), source.client_upload_id(), source.original_key(),
            source.thumb_key(), source.original(), source.thumbnail(), source.latitude(),
            source.longitude(), 12.0, "camera_foreground", source.taken_at(), source.caption(),
            "matched", placeId, visibility, consent
        );
    }

    private long canonicalPlace(String name, double latitude, double longitude) {
        String suffix = java.util.UUID.randomUUID().toString();
        long sourceId = jdbc.sql(
                "INSERT INTO catalog_sources (source_key, name, provider_type, active) "
                    + "VALUES (:key, 'Photo fixture', 'public_data', TRUE) RETURNING id"
            )
            .param("key", "photo-" + suffix)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'Photo fixture license', CURRENT_TIMESTAMP,
                    DATE '2020-01-01', TRUE, '["name"]'::jsonb, :digest
                )
                RETURNING id
                """
            )
            .param("sourceId", sourceId)
            .param("digest", "photo-license-" + suffix)
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
            .param("externalId", "photo-place-" + suffix)
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
            .param("externalId", "photo-record-" + suffix)
            .param("digest", "photo-source-" + suffix)
            .update();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return placeId;
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

    private PhotoResponses.Detail create(long userId, PhotoRequests.Create request) {
        upload(request);
        return photos.create(userId, request);
    }

    private void upload(PhotoRequests.Create request) {
        authorizeUpload(request);
        String userId = request.original_key().split("/")[2];
        objects.put(
            request.original_key(),
            request.original_key(),
            request.original().content_type(),
            request.original().size_bytes(),
            metadata(userId, request, request.original(), "normalized-original")
        );
        objects.put(
            request.thumb_key(),
            request.thumb_key(),
            request.thumbnail().content_type(),
            request.thumbnail().size_bytes(),
            metadata(userId, request, request.thumbnail(), "thumbnail")
        );
    }

    private void authorizeUpload(PhotoRequests.Create request) {
        String userId = request.original_key().split("/")[2];
        GcsSignedUrlService.PreparedUpload prepared = storage.prepareUpload(
            userId,
            request.uploadRequest()
        );
        assertThat(prepared.originalObjectKey()).isEqualTo(request.original_key());
        assertThat(prepared.thumbnailObjectKey()).isEqualTo(request.thumb_key());
    }

    private java.util.Map<String, String> metadata(
        String userId,
        PhotoRequests.Create request,
        PhotoRequests.UploadObject object,
        String derivative
    ) {
        return java.util.Map.of(
            "stog-account-id", userId,
            "stog-trip-id", request.trip_id().toString(),
            "stog-member-id", userId,
            "stog-upload-id", request.client_upload_id(),
            "stog-derivative", derivative,
            "stog-size", object.size_bytes().toString(),
            "stog-sha256", object.sha256()
        );
    }

    private PhotoRequests.Create request(
        long userId,
        long tripId,
        String source,
        Double latitude,
        Double longitude
    ) {
        uploadSequence++;
        String uploadId = "00000000-0000-0000-0000-%012d".formatted(uploadSequence);
        return new PhotoRequests.Create(
            tripId,
            source,
            uploadId,
            "photos/accounts/%d/trips/%d/members/%d/uploads/%s/original.jpg".formatted(
                userId, tripId, userId, uploadId
            ),
            "photos/accounts/%d/trips/%d/members/%d/uploads/%s/thumbnail.jpg".formatted(
                userId, tripId, userId, uploadId
            ),
            new PhotoRequests.UploadObject("image/jpeg", 128L, "a".repeat(64)),
            new PhotoRequests.UploadObject("image/jpeg", 96L, "b".repeat(64)),
            latitude,
            longitude,
            TAKEN_AT,
            "task 12 photo"
        );
    }

    private long user(String nickname) {
        return jdbc.sql("INSERT INTO users (nickname) VALUES (:nickname) RETURNING id")
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private long moderator(String nickname) {
        long userId = user(nickname);
        jdbc.sql("UPDATE users SET is_moderator = TRUE WHERE id = :userId")
            .param("userId", userId)
            .update();
        return userId;
    }

    private long trip(long ownerId, String visibility) {
        return jdbc.sql(
                """
                INSERT INTO trips (owner_id, title, activity_type, visibility, is_group)
                VALUES (:ownerId, :title, 'tour', :visibility, TRUE)
                RETURNING id
                """
            )
            .param("ownerId", ownerId)
            .param("title", "task12 " + visibility + " trip")
            .param("visibility", visibility)
            .query(Long.class)
            .single();
    }

    private void activeMember(long tripId, long userId) {
        jdbc.sql("INSERT INTO trip_members (trip_id, user_id) VALUES (:tripId, :userId)")
            .param("tripId", tripId)
            .param("userId", userId)
            .update();
    }

    private long photoCount(String originalKey) {
        return jdbc.sql("SELECT COUNT(*) FROM photos WHERE original_key = :originalKey")
            .param("originalKey", originalKey)
            .query(Long.class)
            .single();
    }

    private boolean isPubliclyEligible(long photoId) {
        return jdbc.sql(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM photos p
                    JOIN trips t ON t.id = p.trip_id
                    WHERE p.id = :photoId
                      AND t.visibility = 'public'
                      AND p.visibility = 'public'
                      AND p.moderation_status <> 'blocked'
                )
                """
            )
            .param("photoId", photoId)
            .query(Boolean.class)
            .single();
    }

    private void assertProjection(long cellId, long photoId) {
        assertThat(jdbc.sql(
                """
                SELECT public_photo_count || '|' || public_photo_like_count || '|' || top_photo_id
                FROM cell_stats
                WHERE cell_id = :cellId
                """
            )
            .param("cellId", cellId)
            .query(String.class)
            .single()).isEqualTo("1|0|" + photoId);
    }

    private void assertBadRequest(Runnable action) {
        assertStatus(action, HttpStatus.BAD_REQUEST);
    }

    private void assertForbidden(Runnable action) {
        assertStatus(action, HttpStatus.FORBIDDEN);
    }

    private void assertConflict(Runnable action) {
        assertStatus(action, HttpStatus.CONFLICT);
    }

    private void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(expected);
    }
}
