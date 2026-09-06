package com.stog.backend.storage;

import com.stog.backend.plan.EffectiveVisibility;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PhotoRepository {
    private final JdbcClient jdbc;

    public PhotoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireTripOwner(long userId, long tripId) {
        boolean owner = jdbc.sql(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM trips
                    WHERE id = :tripId AND owner_id = :userId
                )
                """
            )
            .params(Map.of("tripId", tripId, "userId", userId))
            .query(Boolean.class)
            .single();
        if (!owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trip access denied");
        }
    }

    public StoredPhoto create(
        long userId,
        PhotoRequests.Create request,
        Long cellId
    ) {
        return createIfAbsent(userId, request, cellId)
            .orElseThrow(() -> new IllegalStateException("photo object keys already exist"));
    }

    public Optional<StoredPhoto> createIfAbsent(
        long userId,
        PhotoRequests.Create request,
        Long cellId
    ) {
        return createIfAbsent(userId, request, cellId, null);
    }

    public Optional<StoredPhoto> createIfAbsent(
        long userId,
        PhotoRequests.Create request,
        Long cellId,
        String finalizeFingerprint
    ) {
        String publicationStatus = "public".equals(request.visibility())
            ? "moderation_pending"
            : "private";
        PhotoPlaceResolver.Resolution place = "no_match".equals(request.place_resolution_status())
            ? new PhotoPlaceResolver.Resolution("no_match", null, null)
            : null;
        return createIfAbsent(
            userId, request, cellId, finalizeFingerprint, place, publicationStatus
        );
    }

    public Optional<StoredPhoto> createIfAbsent(
        long userId,
        PhotoRequests.Create request,
        Long cellId,
        String finalizeFingerprint,
        PhotoPlaceResolver.Resolution place,
        String publicationStatus
    ) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tripId", request.trip_id());
        parameters.put("userId", userId);
        parameters.put("source", request.source());
        parameters.put("cellId", cellId);
        parameters.put("latitude", request.latitude());
        parameters.put("longitude", request.longitude());
        parameters.put("accuracy", request.accuracy_m());
        parameters.put("locationProvenance", request.location_provenance());
        parameters.put(
            "takenAt",
            request.taken_at() == null ? null : Timestamp.from(request.taken_at())
        );
        parameters.put("originalKey", request.original_key());
        parameters.put("thumbKey", request.thumb_key());
        parameters.put("caption", request.caption());
        String placeResolutionStatus = place == null ? null : place.status();
        parameters.put("placeId", place == null ? null : place.placeId());
        parameters.put("placeName", place == null ? null : place.placeName());
        parameters.put("placeResolutionStatus", placeResolutionStatus);
        String requestedVisibility = request.visibility() == null ? "private" : request.visibility();
        boolean publicConsent = Boolean.TRUE.equals(request.public_consent());
        parameters.put("visibility", requestedVisibility);
        parameters.put("publicConsent", publicConsent);
        parameters.put("publicationStatus", publicationStatus);
        parameters.put("finalizeTripId", finalizeFingerprint == null ? null : request.trip_id());
        parameters.put(
            "clientUploadId",
            finalizeFingerprint == null
                ? null
                : java.util.UUID.fromString(request.client_upload_id())
        );
        parameters.put("finalizeFingerprint", finalizeFingerprint);
        parameters.put(
            "originalSizeBytes",
            finalizeFingerprint == null ? null : request.original().size_bytes()
        );
        parameters.put(
            "originalSha256",
            finalizeFingerprint == null ? null : request.original().sha256()
        );
        parameters.put(
            "thumbSizeBytes",
            finalizeFingerprint == null ? null : request.thumbnail().size_bytes()
        );
        parameters.put(
            "thumbSha256",
            finalizeFingerprint == null ? null : request.thumbnail().sha256()
        );
        parameters.put("finalizeVisibility", finalizeFingerprint == null ? null : requestedVisibility);
        parameters.put("finalizeModeration", finalizeFingerprint == null ? null : "pending");
        parameters.put("finalizePlaceId", finalizeFingerprint == null || place == null
            ? null : place.placeId());
        parameters.put("finalizePlaceName", finalizeFingerprint == null || place == null
            ? null : place.placeName());
        parameters.put("finalizePublication", finalizeFingerprint == null ? null : publicationStatus);
        parameters.put(
            "finalizePlaceResolutionStatus",
            finalizeFingerprint == null ? null : placeResolutionStatus
        );
        return jdbc.sql(
                """
                INSERT INTO photos (
                    trip_id,
                    user_id,
                    source,
                    cell_id,
                    lat,
                    lng,
                    location_accuracy_m,
                    location_provenance,
                    taken_at,
                    original_key,
                    thumb_key,
                    caption,
                    place_id,
                    place_name_snapshot,
                    place_resolution_status,
                    visibility,
                    public_consent,
                    publication_status,
                    finalize_trip_id,
                    client_upload_id,
                    finalize_fingerprint,
                    original_size_bytes,
                    original_sha256,
                    thumb_size_bytes,
                    thumb_sha256,
                    finalize_visibility,
                    finalize_moderation_status,
                    finalize_place_id,
                    finalize_place_name_snapshot,
                    finalize_place_resolution_status,
                    finalize_publication_status
                )
                VALUES (
                    :tripId,
                    :userId,
                    :source,
                    :cellId,
                    :latitude,
                    :longitude,
                    :accuracy,
                    :locationProvenance,
                    :takenAt,
                    :originalKey,
                    :thumbKey,
                    :caption,
                    :placeId,
                    :placeName,
                    :placeResolutionStatus,
                    :visibility,
                    :publicConsent,
                    :publicationStatus,
                    :finalizeTripId,
                    :clientUploadId,
                    :finalizeFingerprint,
                    :originalSizeBytes,
                    :originalSha256,
                    :thumbSizeBytes,
                    :thumbSha256,
                    :finalizeVisibility,
                    :finalizeModeration,
                    :finalizePlaceId,
                    :finalizePlaceName,
                    :finalizePlaceResolutionStatus,
                    :finalizePublication
                )
                ON CONFLICT DO NOTHING
                RETURNING id, trip_id, user_id, source, cell_id, lat, lng,
                    location_accuracy_m, location_provenance, taken_at,
                    original_key, thumb_key, caption, place_id, place_name_snapshot,
                    place_resolution_status, visibility, moderation_status,
                    public_consent, publication_status, created_at
                """
            )
            .params(parameters)
            .query(this::map)
            .optional();
    }

    public String tripVisibility(long tripId) {
        return jdbc.sql("SELECT visibility FROM trips WHERE id = :tripId")
            .param("tripId", tripId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new PhotoApiException(
                HttpStatus.FORBIDDEN, "PHOTO_ACCESS_DENIED", "Trip access denied"
            ));
    }

    public Optional<StoredPhoto> findByOriginalKey(String originalKey) {
        return jdbc.sql(selectPhoto("WHERE p.original_key = :originalKey"))
            .param("originalKey", originalKey)
            .query(this::map)
            .optional();
    }

    public void lockFinalizeIdentity(long userId, String clientUploadId) {
        java.util.UUID uploadId = java.util.UUID.fromString(clientUploadId);
        long uploadBits = uploadId.getMostSignificantBits() ^ uploadId.getLeastSignificantBits();
        long lockKey = uploadBits ^ Long.rotateLeft(userId, 17);
        jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
            .param("lockKey", lockKey)
            .query((row, rowNumber) -> 1)
            .single();
    }

    public Optional<FinalizedPhoto> findFinalized(
        long userId,
        String clientUploadId
    ) {
        java.util.UUID uploadId;
        try {
            uploadId = java.util.UUID.fromString(clientUploadId);
        } catch (IllegalArgumentException | NullPointerException error) {
            return Optional.empty();
        }
        return jdbc.sql(
                """
                SELECT p.id, p.trip_id, p.user_id, p.source, p.cell_id, p.lat,
                    p.lng, p.location_accuracy_m, p.location_provenance, p.taken_at,
                    p.original_key, p.thumb_key, p.caption,
                    CASE
                        WHEN EXISTS (
                            SELECT 1 FROM places current_place
                            WHERE current_place.id = p.place_id
                              AND current_place.catalog_status <> 'quarantined'
                        ) THEN p.place_id
                        ELSE NULL
                    END AS place_id,
                    p.place_name_snapshot, p.place_resolution_status,
                    p.visibility, p.moderation_status, p.public_consent,
                    %s AS publication_status, p.created_at, p.finalize_trip_id,
                    p.client_upload_id, p.finalize_fingerprint, p.finalize_visibility,
                    p.finalize_moderation_status, p.finalize_place_id,
                    p.finalize_place_name_snapshot, p.finalize_place_resolution_status,
                    p.finalize_publication_status
                FROM photos p
                LEFT JOIN trips t ON t.id = p.trip_id
                WHERE p.user_id = :userId
                  AND p.client_upload_id = :uploadId
                """.formatted(EffectiveVisibility.CURRENT_PHOTO_PUBLICATION_STATUS_SQL)
            )
            .params(Map.of(
                "userId", userId,
                "uploadId", uploadId
            ))
            .query((row, rowNumber) -> new FinalizedPhoto(
                map(row, rowNumber),
                row.getLong("finalize_trip_id"),
                row.getObject("client_upload_id", java.util.UUID.class).toString(),
                row.getString("finalize_fingerprint"),
                row.getString("finalize_visibility"),
                row.getString("finalize_moderation_status"),
                nullableLong(row.getObject("finalize_place_id")),
                row.getString("finalize_place_name_snapshot"),
                row.getString("finalize_place_resolution_status"),
                row.getString("finalize_publication_status")
            ))
            .optional();
    }

    public Optional<StoredPhoto> find(long photoId) {
        return jdbc.sql(selectPhoto("WHERE p.id = :photoId"))
            .param("photoId", photoId)
            .query(this::map)
            .optional();
    }

    public Optional<StoredPhoto> lock(long photoId) {
        return jdbc.sql(selectPhoto("WHERE p.id = :photoId FOR UPDATE OF p"))
            .param("photoId", photoId)
            .query(this::map)
            .optional();
    }

    public Optional<StoredPhoto> lockPubliclyEligible(long photoId) {
        return lockPubliclyEligible(photoId, EffectiveVisibility.LIKE_PHOTO_ELIGIBILITY_SQL);
    }

    public Optional<StoredPhoto> lockFeedEligible(long photoId) {
        return lockPubliclyEligible(photoId, EffectiveVisibility.FEED_PHOTO_ELIGIBILITY_SQL);
    }

    private Optional<StoredPhoto> lockPubliclyEligible(long photoId, String eligibility) {
        return jdbc.sql("""
                SELECT p.id, p.trip_id, p.user_id, p.source, p.cell_id, p.lat,
                    p.lng, p.location_accuracy_m, p.location_provenance, p.taken_at,
                    p.original_key, p.thumb_key, p.caption,
                    CASE
                        WHEN EXISTS (
                            SELECT 1 FROM places current_place
                            WHERE current_place.id = p.place_id
                              AND current_place.catalog_status <> 'quarantined'
                        ) THEN p.place_id
                        ELSE NULL
                    END AS place_id,
                    p.place_name_snapshot, p.place_resolution_status,
                    p.visibility, p.moderation_status, p.public_consent,
                    %s AS publication_status, p.created_at
                FROM photos p
                JOIN trips t ON t.id = p.trip_id
                WHERE p.id = :photoId
                  AND %s
                FOR UPDATE OF p
                """.formatted(
                    EffectiveVisibility.CURRENT_PHOTO_PUBLICATION_STATUS_SQL,
                    eligibility
                ))
            .param("photoId", photoId)
            .query(this::map)
            .optional();
    }

    public List<StoredPhoto> findByTrip(long tripId) {
        return jdbc.sql(selectPhoto("WHERE p.trip_id = :tripId ORDER BY p.created_at DESC, p.id DESC"))
            .param("tripId", tripId)
            .query(this::map)
            .list();
    }

    public List<StoredPhoto> findByUser(long userId) {
        return jdbc.sql(selectPhoto("WHERE p.user_id = :userId ORDER BY p.created_at DESC, p.id DESC"))
            .param("userId", userId)
            .query(this::map)
            .list();
    }

    public Optional<StoredPhoto> findOwned(long userId, long photoId) {
        return jdbc.sql(selectPhoto("WHERE p.id = :photoId AND p.user_id = :userId"))
            .params(Map.of("photoId", photoId, "userId", userId))
            .query(this::map)
            .optional();
    }

    public StoredPhoto updateVisibility(long photoId, String visibility) {
        long updatedId = jdbc.sql(
                """
                UPDATE photos
                SET visibility = :visibility
                WHERE id = :photoId
                RETURNING id
                """
            )
            .param("photoId", photoId)
            .param("visibility", visibility)
            .query(Long.class)
            .single();
        return find(updatedId).orElseThrow();
    }

    public Optional<StoredPhoto> updateVisibility(
        long userId,
        long photoId,
        String visibility
    ) {
        return jdbc.sql(
                """
                UPDATE photos
                SET visibility = :visibility
                WHERE id = :photoId
                  AND user_id = :userId
                RETURNING id
                """
            )
            .params(Map.of(
                "photoId", photoId,
                "userId", userId,
                "visibility", visibility
            ))
            .query(Long.class)
            .optional()
            .flatMap(this::find);
    }

    public boolean isModerator(long userId) {
        return jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id = :userId AND is_moderator)"
            )
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public boolean hasActiveGrant(long photoId) {
        return jdbc.sql(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM photo_public_grants
                    WHERE photo_id = :photoId
                      AND revoked_at IS NULL
                )
                """
            )
            .param("photoId", photoId)
            .query(Boolean.class)
            .single();
    }

    public PhotoResponses.PublicGrant createNextGrant(long photoId, long grantedBy) {
        int nextVersion = jdbc.sql(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM photo_public_grants WHERE photo_id = :photoId"
            )
            .param("photoId", photoId)
            .query(Integer.class)
            .single();
        return jdbc.sql(
                """
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (:photoId, :version, :grantedBy)
                RETURNING photo_id, version, granted_by, granted_at, revoked_at, scope
                """
            )
            .params(Map.of(
                "photoId", photoId,
                "version", nextVersion,
                "grantedBy", grantedBy
            ))
            .query(this::mapGrant)
            .single();
    }

    public Optional<PhotoResponses.PublicGrant> revokeGrant(long photoId, int version) {
        return jdbc.sql(
                """
                UPDATE photo_public_grants
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE photo_id = :photoId
                  AND version = :version
                  AND revoked_at IS NULL
                RETURNING photo_id, version, granted_by, granted_at, revoked_at, scope
                """
            )
            .params(Map.of("photoId", photoId, "version", version))
            .query(this::mapGrant)
            .optional();
    }

    public List<PhotoResponses.PublicGrant> findGrants(long photoId) {
        return jdbc.sql(
                """
                SELECT photo_id, version, granted_by, granted_at, revoked_at, scope
                FROM photo_public_grants
                WHERE photo_id = :photoId
                ORDER BY version DESC
                """
            )
            .param("photoId", photoId)
            .query(this::mapGrant)
            .list();
    }

    public void updateModerationStatus(long photoId, String toStatus) {
        jdbc.sql(
                "UPDATE photos SET moderation_status = :toStatus WHERE id = :photoId"
            )
            .param("photoId", photoId)
            .param("toStatus", toStatus)
            .update();
    }

    public long incrementLikeCount(long photoId) {
        return jdbc.sql("""
                UPDATE photos
                SET like_count = like_count + 1
                WHERE id = :photoId
                RETURNING like_count
                """)
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    public long decrementLikeCount(long photoId) {
        return jdbc.sql("""
                UPDATE photos
                SET like_count = like_count - 1
                WHERE id = :photoId
                RETURNING like_count
                """)
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    public long likeCount(long photoId) {
        return jdbc.sql("SELECT like_count FROM photos WHERE id = :photoId")
            .param("photoId", photoId)
            .query(Long.class)
            .single();
    }

    public PhotoResponses.Moderation appendModerationEvent(
        long photoId,
        long moderatorId,
        String fromStatus,
        String toStatus,
        String reason
    ) {
        return jdbc.sql(
                """
                INSERT INTO photo_moderation_events (
                    photo_id, moderator_id, from_status, to_status, reason
                )
                VALUES (:photoId, :moderatorId, :fromStatus, :toStatus, :reason)
                RETURNING photo_id, moderator_id, from_status, to_status, reason, created_at
                """
            )
            .param("photoId", photoId)
            .param("moderatorId", moderatorId)
            .param("fromStatus", fromStatus)
            .param("toStatus", toStatus)
            .param("reason", reason)
            .query((row, rowNumber) -> new PhotoResponses.Moderation(
                row.getLong("photo_id"),
                row.getString("from_status"),
                row.getString("to_status"),
                row.getLong("moderator_id"),
                row.getString("reason"),
                instant(row.getTimestamp("created_at"))
            ))
            .single();
    }

    public void refreshCellProjection(Long cellId) {
        if (cellId == null) {
            return;
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:cellId)")
            .param("cellId", cellId)
            .query((row, rowNumber) -> 1)
            .single();
        Long landmarkCount = jdbc.sql(
                "SELECT landmark_count FROM cell_stats WHERE cell_id = :cellId FOR UPDATE"
            )
            .param("cellId", cellId)
            .query(Long.class)
            .optional()
            .orElse(null);
        ProjectionCounts counts = publicProjectionCounts(cellId);
        if (landmarkCount == null && counts.photoCount() == 0) {
            return;
        }
        if (landmarkCount != null && landmarkCount == 0 && counts.photoCount() == 0) {
            jdbc.sql("DELETE FROM cell_stats WHERE cell_id = :cellId")
                .param("cellId", cellId)
                .update();
            return;
        }
        if (landmarkCount == null) {
            jdbc.sql(
                    """
                    INSERT INTO cell_stats (
                        cell_id, landmark_count, public_photo_count,
                        public_photo_like_count, top_photo_id
                    )
                    VALUES (:cellId, 0, :photoCount, :likeCount, :topPhotoId)
                    """
                )
                .param("cellId", cellId)
                .param("photoCount", counts.photoCount())
                .param("likeCount", counts.likeCount())
                .param("topPhotoId", counts.topPhotoId())
                .update();
            return;
        }
        jdbc.sql(
                """
                UPDATE cell_stats
                SET public_photo_count = :photoCount,
                    public_photo_like_count = :likeCount,
                    top_photo_id = :topPhotoId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE cell_id = :cellId
                """
            )
            .param("cellId", cellId)
            .param("photoCount", counts.photoCount())
            .param("likeCount", counts.likeCount())
            .param("topPhotoId", counts.topPhotoId())
            .update();
    }

    private ProjectionCounts publicProjectionCounts(long cellId) {
        ProjectionCounts counts = jdbc.sql(
                """
                SELECT COUNT(*) AS photo_count,
                       COALESCE(SUM(p.like_count), 0) AS like_count
                FROM photos p
                JOIN trips t ON t.id = p.trip_id
                WHERE p.cell_id = :cellId
                  AND %s
                """.formatted(EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL)
            )
            .param("cellId", cellId)
            .query((row, rowNumber) -> new ProjectionCounts(
                row.getLong("photo_count"),
                row.getLong("like_count"),
                null
            ))
            .single();
        if (counts.photoCount() == 0) {
            return counts;
        }
        Long topPhotoId = jdbc.sql(
                """
                SELECT p.id
                FROM photos p
                JOIN trips t ON t.id = p.trip_id
                WHERE p.cell_id = :cellId
                  AND %s
                ORDER BY p.like_count DESC, p.id ASC
                LIMIT 1
                """.formatted(EffectiveVisibility.CELL_PHOTO_ELIGIBILITY_SQL)
            )
            .param("cellId", cellId)
            .query(Long.class)
            .single();
        return new ProjectionCounts(counts.photoCount(), counts.likeCount(), topPhotoId);
    }

    private String selectPhoto(String where) {
        return """
            SELECT p.id, p.trip_id, p.user_id, p.source, p.cell_id, p.lat,
                p.lng, p.location_accuracy_m, p.location_provenance, p.taken_at,
                p.original_key, p.thumb_key, p.caption,
                CASE
                    WHEN EXISTS (
                        SELECT 1 FROM places current_place
                        WHERE current_place.id = p.place_id
                          AND current_place.catalog_status <> 'quarantined'
                    ) THEN p.place_id
                    ELSE NULL
                END AS place_id,
                p.place_name_snapshot, p.place_resolution_status,
                p.visibility, p.moderation_status, p.public_consent,
                %s AS publication_status, p.created_at
            FROM photos p
            LEFT JOIN trips t ON t.id = p.trip_id
            %s
            """.formatted(EffectiveVisibility.CURRENT_PHOTO_PUBLICATION_STATUS_SQL, where);
    }

    private StoredPhoto map(java.sql.ResultSet row, int rowNumber)
        throws java.sql.SQLException {
        return new StoredPhoto(
            row.getLong("id"),
            nullableLong(row.getObject("trip_id")),
            row.getLong("user_id"),
            row.getString("source"),
            nullableLong(row.getObject("cell_id")),
            row.getObject("lat", Double.class),
            row.getObject("lng", Double.class),
            row.getObject("location_accuracy_m", Double.class),
            row.getString("location_provenance"),
            instant(row.getTimestamp("taken_at")),
            row.getString("original_key"),
            row.getString("thumb_key"),
            row.getString("caption"),
            nullableLong(row.getObject("place_id")),
            row.getString("place_name_snapshot"),
            row.getString("place_resolution_status"),
            row.getString("visibility"),
            row.getString("moderation_status"),
            row.getBoolean("public_consent"),
            row.getString("publication_status"),
            instant(row.getTimestamp("created_at"))
        );
    }

    private PhotoResponses.PublicGrant mapGrant(
        java.sql.ResultSet row,
        int rowNumber
    ) throws java.sql.SQLException {
        Array sqlScope = row.getArray("scope");
        String[] scope = (String[]) sqlScope.getArray();
        List<String> scopeValues = new ArrayList<>(List.of(scope));
        return new PhotoResponses.PublicGrant(
            row.getLong("photo_id"),
            row.getInt("version"),
            row.getLong("granted_by"),
            instant(row.getTimestamp("granted_at")),
            instant(row.getTimestamp("revoked_at")),
            List.copyOf(scopeValues)
        );
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record FinalizedPhoto(
        StoredPhoto photo,
        long finalizeTripId,
        String clientUploadId,
        String finalizeFingerprint,
        String finalizeVisibility,
        String finalizeModerationStatus,
        Long finalizePlaceId,
        String finalizePlaceNameSnapshot,
        String finalizePlaceResolutionStatus,
        String finalizePublicationStatus
    ) {
        public FinalizedPhoto(
            StoredPhoto photo,
            long finalizeTripId,
            String clientUploadId,
            String finalizeFingerprint,
            String finalizeVisibility,
            String finalizeModerationStatus
        ) {
            this(
                photo, finalizeTripId, clientUploadId, finalizeFingerprint,
                finalizeVisibility, finalizeModerationStatus, photo.placeId(),
                photo.placeNameSnapshot(), photo.placeResolutionStatus(),
                photo.publicationStatus()
            );
        }
    }

    public record StoredPhoto(
        long id,
        Long tripId,
        long userId,
        String source,
        Long cellId,
        Double latitude,
        Double longitude,
        Double accuracyMeters,
        String locationProvenance,
        Instant takenAt,
        String originalKey,
        String thumbKey,
        String caption,
        Long placeId,
        String placeNameSnapshot,
        String placeResolutionStatus,
        String visibility,
        String moderationStatus,
        boolean publicConsent,
        String publicationStatus,
        Instant createdAt
    ) {
        public StoredPhoto(
            long id,
            Long tripId,
            long userId,
            String source,
            Long cellId,
            Double latitude,
            Double longitude,
            Instant takenAt,
            String originalKey,
            String thumbKey,
            String caption,
            String visibility,
            String moderationStatus,
            Instant createdAt
        ) {
            this(
                id, tripId, userId, source, cellId, latitude, longitude,
                null, null, takenAt, originalKey, thumbKey, caption,
                null, null, null, visibility, moderationStatus, false,
                "public".equals(visibility) ? "public" : "private",
                createdAt
            );
        }
    }

    private record ProjectionCounts(long photoCount, long likeCount, Long topPhotoId) {
    }
}
