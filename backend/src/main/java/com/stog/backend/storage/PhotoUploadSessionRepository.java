package com.stog.backend.storage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoUploadSessionRepository {
    private final JdbcClient jdbc;

    public PhotoUploadSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UploadSession createOrFind(
        long userId,
        GcsSignedUrlService.PreparedUpload upload,
        String uploadFingerprint
    ) {
        PhotoRequests.UploadUrl request = upload.request();
        jdbc.sql(
                """
                INSERT INTO photo_upload_sessions (
                    user_id, trip_id, client_upload_id, original_key, thumb_key,
                    original_content_type, original_size_bytes, original_sha256,
                    thumb_content_type, thumb_size_bytes, thumb_sha256,
                    upload_fingerprint, expires_at
                )
                VALUES (
                    :userId, :tripId, :uploadId, :originalKey, :thumbKey,
                    :originalType, :originalSize, :originalSha,
                    :thumbType, :thumbSize, :thumbSha, :fingerprint, :expiresAt
                )
                ON CONFLICT DO NOTHING
                """
            )
            .params(Map.ofEntries(
                Map.entry("userId", userId),
                Map.entry("tripId", request.trip_id()),
                Map.entry("uploadId", UUID.fromString(request.client_upload_id())),
                Map.entry("originalKey", upload.originalObjectKey()),
                Map.entry("thumbKey", upload.thumbnailObjectKey()),
                Map.entry("originalType", request.original().content_type()),
                Map.entry("originalSize", request.original().size_bytes()),
                Map.entry("originalSha", request.original().sha256()),
                Map.entry("thumbType", request.thumbnail().content_type()),
                Map.entry("thumbSize", request.thumbnail().size_bytes()),
                Map.entry("thumbSha", request.thumbnail().sha256()),
                Map.entry("fingerprint", uploadFingerprint),
                Map.entry("expiresAt", Timestamp.from(upload.expiresAt()))
            ))
            .update();
        return find(userId, request.trip_id(), request.client_upload_id())
            .orElseThrow(() -> new IllegalStateException("photo upload session was not stored"));
    }

    public Optional<UploadSession> find(
        long userId,
        long tripId,
        String clientUploadId
    ) {
        UUID uploadId;
        try {
            uploadId = UUID.fromString(clientUploadId);
        } catch (IllegalArgumentException | NullPointerException error) {
            return Optional.empty();
        }
        return jdbc.sql(
                """
                SELECT user_id, trip_id, client_upload_id, original_key, thumb_key,
                    original_content_type, original_size_bytes, original_sha256,
                    thumb_content_type, thumb_size_bytes, thumb_sha256,
                    upload_fingerprint, expires_at, created_at
                FROM photo_upload_sessions
                WHERE user_id = :userId
                  AND trip_id = :tripId
                  AND client_upload_id = :uploadId
                """
            )
            .params(Map.of(
                "userId", userId,
                "tripId", tripId,
                "uploadId", uploadId
            ))
            .query((row, rowNumber) -> new UploadSession(
                row.getLong("user_id"),
                row.getLong("trip_id"),
                row.getObject("client_upload_id", UUID.class).toString(),
                row.getString("original_key"),
                row.getString("thumb_key"),
                row.getString("original_content_type"),
                row.getLong("original_size_bytes"),
                row.getString("original_sha256"),
                row.getString("thumb_content_type"),
                row.getLong("thumb_size_bytes"),
                row.getString("thumb_sha256"),
                row.getString("upload_fingerprint"),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("created_at").toInstant()
            ))
            .optional();
    }

    public void delete(long userId, long tripId, String clientUploadId) {
        jdbc.sql(
                """
                DELETE FROM photo_upload_sessions
                WHERE user_id = :userId
                  AND trip_id = :tripId
                  AND client_upload_id = :uploadId
                """
            )
            .params(Map.of(
                "userId", userId,
                "tripId", tripId,
                "uploadId", UUID.fromString(clientUploadId)
            ))
            .update();
    }

    public record UploadSession(
        long userId,
        long tripId,
        String clientUploadId,
        String originalKey,
        String thumbKey,
        String originalContentType,
        long originalSizeBytes,
        String originalSha256,
        String thumbContentType,
        long thumbSizeBytes,
        String thumbSha256,
        String uploadFingerprint,
        Instant expiresAt,
        Instant createdAt
    ) {
        PhotoRequests.UploadUrl uploadRequest() {
            return new PhotoRequests.UploadUrl(
                tripId,
                clientUploadId,
                new PhotoRequests.UploadObject(
                    originalContentType,
                    originalSizeBytes,
                    originalSha256
                ),
                new PhotoRequests.UploadObject(
                    thumbContentType,
                    thumbSizeBytes,
                    thumbSha256
                )
            );
        }
    }
}
