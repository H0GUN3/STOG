package com.stog.backend.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("gcs-write")
public class GcsSignedUrlService implements GcsReadUrlSigner {
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";
    private static final String DERIVATIVE_METADATA_KEY = "stog-derivative";
    private static final String NORMALIZED_ORIGINAL = "normalized-original";
    private static final String THUMBNAIL = "thumbnail";
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";
    private static final String UUID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern OBJECT_KEY = Pattern.compile(
        "^photos/accounts/([1-9][0-9]*)/trips/([1-9][0-9]*)/members/([1-9][0-9]*)/"
            + "uploads/(" + UUID_PATTERN + ")/(original|thumbnail)\\.jpg$"
    );
    private static final Pattern LEGACY_OBJECT_KEY = Pattern.compile(
        "^photos/([A-Za-z0-9_-]+)/(" + UUID_PATTERN + ")(-thumb)?\\.(jpg|png|webp|heic)$"
    );
    private static final Pattern PROFILE_IMAGE_OBJECT_KEY = Pattern.compile(
        "^profile-images/users/([1-9][0-9]*)/uploads/(" + UUID_PATTERN + ")\\.jpg$"
    );
    private static final Pattern TRIP_COVER_IMAGE_OBJECT_KEY = Pattern.compile(
        "^trip-covers/accounts/([1-9][0-9]*)/trips/([1-9][0-9]*)/uploads/("
            + UUID_PATTERN + ")\\.jpg$"
    );
    private static final Pattern CATALOG_IMAGE_OBJECT_KEY = Pattern.compile(
        "^catalog-images/v1/source-images/[1-9][0-9]*$"
    );
    private static final String PROFILE_IMAGE_MEDIA_TYPE = "profile-image";
    private static final String TRIP_COVER_IMAGE_MEDIA_TYPE = "trip-cover-image";

    private final GcsObjectClient objects;
    private final StorageProperties properties;
    private final Clock clock;

    public GcsSignedUrlService(
        GcsObjectClient objects,
        StorageProperties properties,
        Clock clock
    ) {
        this.objects = objects;
        this.properties = properties;
        this.clock = clock;
    }

    public PhotoResponses.UploadUrl issueUploadUrl(
        String userId,
        PhotoRequests.UploadUrl request
    ) {
        return issueUploadUrl(prepareUpload(userId, request));
    }

    public PreparedUpload prepareUpload(
        String userId,
        PhotoRequests.UploadUrl request
    ) {
        validateUploadRequest(userId, request);
        Duration ttl = signedUrlTtl();
        String originalObjectKey = objectKey(
            userId,
            request.trip_id(),
            request.client_upload_id(),
            "original"
        );
        String thumbnailObjectKey = objectKey(
            userId,
            request.trip_id(),
            request.client_upload_id(),
            "thumbnail"
        );
        return new PreparedUpload(
            userId,
            request,
            originalObjectKey,
            thumbnailObjectKey,
            expectedMetadata(userId, request, request.original(), NORMALIZED_ORIGINAL),
            expectedMetadata(userId, request, request.thumbnail(), THUMBNAIL),
            ttl,
            clock.instant().plus(ttl)
        );
    }

    public ProfileImageUpload issueProfileImageUpload(
        String userId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateProfileImageUpload(userId, uploadId, contentType, sizeBytes, sha256);
        Duration ttl = signedUrlTtl();
        String objectKey = profileImageObjectKey(userId, uploadId);
        Map<String, String> metadata = profileImageMetadata(userId, uploadId, sizeBytes, sha256);
        return new ProfileImageUpload(
            objectKey,
            objects.signPut(objectKey, JPEG_CONTENT_TYPE, sha256, metadata, ttl),
            JPEG_CONTENT_TYPE,
            uploadHeaders(contentType, sha256, metadata),
            clock.instant().plus(ttl)
        );
    }

    public String verifyProfileImageUpload(
        String userId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateProfileImageUpload(userId, uploadId, contentType, sizeBytes, sha256);
        String objectKey = profileImageObjectKey(userId, uploadId);
        GcsObjectClient.ObjectMetadata actual = objects.find(objectKey)
            .orElseThrow(this::invalidProfileImageMetadata);
        Map<String, String> metadata = profileImageMetadata(userId, uploadId, sizeBytes, sha256);
        if (!objectKey.equals(actual.objectKey())
            || !JPEG_CONTENT_TYPE.equals(actual.contentType())
            || actual.sizeBytes() != sizeBytes
            || actual.sizeBytes() > properties.originalMaxBytes()
            || actual.crc32c() == null
            || actual.crc32c().isBlank()
            || !metadata.equals(actual.metadata())) {
            throw invalidProfileImageMetadata();
        }
        GcsObjectClient.ContentDigest digest = objects.digest(objectKey, properties.originalMaxBytes());
        if (digest.sizeBytes() != actual.sizeBytes()
            || !sha256.equals(digest.sha256())
            || !actual.crc32c().equals(digest.crc32c())) {
            throw new IllegalArgumentException("profile image object content digest is invalid");
        }
        return objectKey;
    }

    public TripCoverImageUpload issueTripCoverImageUpload(
        String userId,
        long tripId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateTripCoverImageUpload(userId, tripId, uploadId, contentType, sizeBytes, sha256);
        Duration ttl = signedUrlTtl();
        String objectKey = tripCoverImageObjectKey(userId, tripId, uploadId);
        Map<String, String> metadata = tripCoverImageMetadata(
            userId,
            tripId,
            uploadId,
            sizeBytes,
            sha256
        );
        return new TripCoverImageUpload(
            objectKey,
            objects.signPut(objectKey, JPEG_CONTENT_TYPE, sha256, metadata, ttl),
            JPEG_CONTENT_TYPE,
            uploadHeaders(contentType, sha256, metadata),
            clock.instant().plus(ttl)
        );
    }

    public String verifyTripCoverImageUpload(
        String userId,
        long tripId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateTripCoverImageUpload(userId, tripId, uploadId, contentType, sizeBytes, sha256);
        String objectKey = tripCoverImageObjectKey(userId, tripId, uploadId);
        GcsObjectClient.ObjectMetadata actual = objects.find(objectKey)
            .orElseThrow(this::invalidTripCoverImageMetadata);
        Map<String, String> metadata = tripCoverImageMetadata(
            userId,
            tripId,
            uploadId,
            sizeBytes,
            sha256
        );
        if (!objectKey.equals(actual.objectKey())
            || !JPEG_CONTENT_TYPE.equals(actual.contentType())
            || actual.sizeBytes() != sizeBytes
            || actual.sizeBytes() > properties.originalMaxBytes()
            || actual.crc32c() == null
            || actual.crc32c().isBlank()
            || !metadata.equals(actual.metadata())) {
            throw invalidTripCoverImageMetadata();
        }
        GcsObjectClient.ContentDigest digest = objects.digest(objectKey, properties.originalMaxBytes());
        if (digest.sizeBytes() != actual.sizeBytes()
            || !sha256.equals(digest.sha256())
            || !actual.crc32c().equals(digest.crc32c())) {
            throw new IllegalArgumentException("trip cover image object content digest is invalid");
        }
        return objectKey;
    }

    public PhotoResponses.UploadUrl issueUploadUrl(PreparedUpload upload) {
        return new PhotoResponses.UploadUrl(
            upload.originalObjectKey(),
            objects.signPut(
                upload.originalObjectKey(),
                JPEG_CONTENT_TYPE,
                upload.request().original().sha256(),
                upload.originalMetadata(),
                upload.ttl()
            ),
            upload.thumbnailObjectKey(),
            objects.signPut(
                upload.thumbnailObjectKey(),
                JPEG_CONTENT_TYPE,
                upload.request().thumbnail().sha256(),
                upload.thumbnailMetadata(),
                upload.ttl()
            ),
            JPEG_CONTENT_TYPE,
            uploadHeaders(upload.request().original(), upload.originalMetadata()),
            uploadHeaders(upload.request().thumbnail(), upload.thumbnailMetadata()),
            upload.expiresAt()
        );
    }

    public void verifyUploadedObjects(
        String userId,
        PhotoRequests.UploadUrl request,
        String originalObjectKey,
        String thumbnailObjectKey
    ) {
        validateUploadRequest(userId, request);
        ObjectPair pair = requireCurrentPair(originalObjectKey, thumbnailObjectKey);
        if (!pair.original().accountId().equals(userId)
            || !pair.original().memberId().equals(userId)
            || !pair.original().tripId().equals(request.trip_id().toString())
            || !pair.original().uploadId().equals(request.client_upload_id())) {
            throw invalidObjectKeys();
        }
        verifyObject(
            pair.original(),
            request.original(),
            expectedMetadata(userId, request, request.original(), NORMALIZED_ORIGINAL),
            properties.originalMaxBytes()
        );
        verifyObject(
            pair.thumbnail(),
            request.thumbnail(),
            expectedMetadata(userId, request, request.thumbnail(), THUMBNAIL),
            properties.thumbnailMaxBytes()
        );
    }

    public PhotoResponses.ReadUrls issueReadUrls(
        String originalObjectKey,
        String thumbnailObjectKey
    ) {
        ObjectPair pair = requireReadablePair(originalObjectKey, thumbnailObjectKey);
        verifyReadableObject(pair.original());
        verifyReadableObject(pair.thumbnail());
        Duration ttl = signedUrlTtl();
        return new PhotoResponses.ReadUrls(
            objects.signRead(pair.original().objectKey(), ttl),
            objects.signRead(pair.thumbnail().objectKey(), ttl)
        );
    }

    @Override
    public URI issueReadUrl(String objectKey) {
        Matcher profileImage = PROFILE_IMAGE_OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (profileImage.matches()) {
            verifyReadableProfileImage(objectKey, profileImage);
            return objects.signRead(objectKey, signedUrlTtl());
        }
        Matcher tripCoverImage = TRIP_COVER_IMAGE_OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (tripCoverImage.matches()) {
            verifyReadableTripCoverImage(objectKey, tripCoverImage);
            return objects.signRead(objectKey, signedUrlTtl());
        }
        if (CATALOG_IMAGE_OBJECT_KEY.matcher(objectKey == null ? "" : objectKey).matches()) {
            return objects.signRead(objectKey, signedUrlTtl());
        }
        ObjectKey parsed = requireReadableObjectKey(objectKey);
        verifyReadableObject(parsed);
        return objects.signRead(parsed.objectKey(), signedUrlTtl());
    }

    public boolean belongsToUser(String userId, String objectKey) {
        if (!validUser(userId)) {
            return false;
        }
        try {
            ObjectKey parsed = requireReadableObjectKey(objectKey);
            return userId.equals(parsed.accountId()) && userId.equals(parsed.memberId());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private ObjectPair requireCurrentPair(
        String originalObjectKey,
        String thumbnailObjectKey
    ) {
        ObjectKey original = requireCurrentObjectKey(originalObjectKey);
        ObjectKey thumbnail = requireCurrentObjectKey(thumbnailObjectKey);
        if (original.thumbnail()
            || !thumbnail.thumbnail()
            || !original.sameUpload(thumbnail)) {
            throw invalidObjectKeys();
        }
        return new ObjectPair(original, thumbnail);
    }

    private ObjectPair requireReadablePair(
        String originalObjectKey,
        String thumbnailObjectKey
    ) {
        ObjectKey original = requireReadableObjectKey(originalObjectKey);
        ObjectKey thumbnail = requireReadableObjectKey(thumbnailObjectKey);
        if (original.thumbnail()
            || !thumbnail.thumbnail()
            || !original.sameUpload(thumbnail)) {
            throw invalidObjectKeys();
        }
        return new ObjectPair(original, thumbnail);
    }

    private ObjectKey requireReadableObjectKey(String objectKey) {
        Matcher current = OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (current.matches()) {
            return currentObjectKey(objectKey, current);
        }
        Matcher legacy = LEGACY_OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (!legacy.matches()) {
            throw invalidObjectKeys();
        }
        String extension = legacy.group(4);
        String contentType = switch (extension) {
            case "jpg" -> JPEG_CONTENT_TYPE;
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            default -> throw invalidObjectKeys();
        };
        return new ObjectKey(
            objectKey,
            legacy.group(1),
            null,
            legacy.group(1),
            legacy.group(2),
            legacy.group(3) != null,
            contentType,
            true
        );
    }

    private ObjectKey requireCurrentObjectKey(String objectKey) {
        Matcher matcher = OBJECT_KEY.matcher(objectKey == null ? "" : objectKey);
        if (!matcher.matches()) {
            throw invalidObjectKeys();
        }
        return currentObjectKey(objectKey, matcher);
    }

    private ObjectKey currentObjectKey(String objectKey, Matcher matcher) {
        return new ObjectKey(
            objectKey,
            matcher.group(1),
            matcher.group(2),
            matcher.group(3),
            matcher.group(4),
            "thumbnail".equals(matcher.group(5)),
            JPEG_CONTENT_TYPE,
            false
        );
    }

    private void verifyObject(
        ObjectKey expected,
        PhotoRequests.UploadObject policy,
        Map<String, String> expectedMetadata,
        long maxBytes
    ) {
        GcsObjectClient.ObjectMetadata actual = objects.find(expected.objectKey())
            .orElseThrow(this::invalidObjectMetadata);
        if (!expected.objectKey().equals(actual.objectKey())
            || !policy.content_type().equals(actual.contentType())
            || actual.sizeBytes() > maxBytes
            || policy.size_bytes() != actual.sizeBytes()
            || actual.crc32c() == null
            || actual.crc32c().isBlank()
            || !expectedMetadata.equals(actual.metadata())) {
            throw invalidObjectMetadata();
        }
        GcsObjectClient.ContentDigest digest = objects.digest(expected.objectKey(), maxBytes);
        if (digest.sizeBytes() != actual.sizeBytes()
            || !policy.sha256().equals(digest.sha256())
            || !actual.crc32c().equals(digest.crc32c())) {
            throw new IllegalArgumentException("photo object content digest is invalid");
        }
    }

    private void verifyReadableObject(ObjectKey expected) {
        GcsObjectClient.ObjectMetadata actual = objects.find(expected.objectKey())
            .orElseThrow(this::invalidObjectMetadata);
        String derivative = expected.thumbnail() ? THUMBNAIL : NORMALIZED_ORIGINAL;
        if (!expected.objectKey().equals(actual.objectKey())
            || !expected.contentType().equals(actual.contentType())
            || actual.sizeBytes() <= 0
            || !derivative.equals(actual.metadata().get(DERIVATIVE_METADATA_KEY))) {
            throw invalidObjectMetadata();
        }
        if (!expected.legacy()) {
            if (!expected.accountId().equals(actual.metadata().get("stog-account-id"))
                || !expected.tripId().equals(actual.metadata().get("stog-trip-id"))
                || !expected.memberId().equals(actual.metadata().get("stog-member-id"))
                || !expected.uploadId().equals(actual.metadata().get("stog-upload-id"))
                || !Long.toString(actual.sizeBytes()).equals(actual.metadata().get("stog-size"))
                || !validDigest(actual.metadata().get("stog-sha256"))) {
                throw invalidObjectMetadata();
            }
        }
    }

    private Map<String, String> expectedMetadata(
        String userId,
        PhotoRequests.UploadUrl request,
        PhotoRequests.UploadObject object,
        String derivative
    ) {
        return Map.of(
            "stog-account-id", userId,
            "stog-trip-id", request.trip_id().toString(),
            "stog-member-id", userId,
            "stog-upload-id", request.client_upload_id(),
            DERIVATIVE_METADATA_KEY, derivative,
            "stog-size", object.size_bytes().toString(),
            "stog-sha256", object.sha256()
        );
    }

    private Map<String, String> uploadHeaders(
        PhotoRequests.UploadObject object,
        Map<String, String> metadata
    ) {
        return uploadHeaders(object.content_type(), object.sha256(), metadata);
    }

    private Map<String, String> uploadHeaders(
        String contentType,
        String sha256,
        Map<String, String> metadata
    ) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("x-goog-content-sha256", sha256);
        metadata.forEach((key, value) -> headers.put("x-goog-meta-" + key, value));
        return Map.copyOf(headers);
    }

    private void validateProfileImageUpload(
        String userId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateUser(userId);
        validateUploadId(uploadId);
        if (!JPEG_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("profile image content_type is unsupported");
        }
        if (sizeBytes == null || sizeBytes < 1 || sizeBytes > properties.originalMaxBytes()) {
            throw new IllegalArgumentException(
                "profile image size_bytes is outside configured bounds"
            );
        }
        if (!validDigest(sha256)) {
            throw new IllegalArgumentException("profile image sha256 is invalid");
        }
        signedUrlTtl();
    }

    private void validateTripCoverImageUpload(
        String userId,
        long tripId,
        String uploadId,
        String contentType,
        Long sizeBytes,
        String sha256
    ) {
        validateUser(userId);
        if (tripId < 1) {
            throw new IllegalArgumentException("trip id is invalid");
        }
        validateUploadId(uploadId);
        if (!JPEG_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("trip cover image content_type is unsupported");
        }
        if (sizeBytes == null || sizeBytes < 1 || sizeBytes > properties.originalMaxBytes()) {
            throw new IllegalArgumentException(
                "trip cover image size_bytes is outside configured bounds"
            );
        }
        if (!validDigest(sha256)) {
            throw new IllegalArgumentException("trip cover image sha256 is invalid");
        }
        signedUrlTtl();
    }

    private void validateUploadRequest(String userId, PhotoRequests.UploadUrl request) {
        validateUser(userId);
        if (request == null || request.trip_id() == null || request.trip_id() <= 0) {
            throw new IllegalArgumentException("trip_id is invalid");
        }
        validateUploadId(request.client_upload_id());
        validateObjectPolicy(
            "original",
            request.original(),
            properties.originalMaxBytes()
        );
        validateObjectPolicy(
            "thumbnail",
            request.thumbnail(),
            properties.thumbnailMaxBytes()
        );
        signedUrlTtl();
    }

    private void validateObjectPolicy(
        String name,
        PhotoRequests.UploadObject object,
        long maxBytes
    ) {
        if (object == null || !JPEG_CONTENT_TYPE.equals(object.content_type())) {
            throw new IllegalArgumentException(name + " content_type is unsupported");
        }
        if (object.size_bytes() == null
            || object.size_bytes() < 1
            || object.size_bytes() > maxBytes) {
            throw new IllegalArgumentException(name + " size_bytes is outside configured bounds");
        }
        if (!validDigest(object.sha256())) {
            throw new IllegalArgumentException(name + " sha256 is invalid");
        }
    }

    private void validateUploadId(String value) {
        if (value == null || !value.matches(UUID_PATTERN)) {
            throw new IllegalArgumentException("client_upload_id is invalid");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("client_upload_id is invalid");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("client_upload_id is invalid");
        }
    }

    private String profileImageObjectKey(String userId, String uploadId) {
        return "profile-images/users/%s/uploads/%s.jpg".formatted(userId, uploadId);
    }

    private String tripCoverImageObjectKey(String userId, long tripId, String uploadId) {
        return "trip-covers/accounts/%s/trips/%d/uploads/%s.jpg".formatted(
            userId,
            tripId,
            uploadId
        );
    }

    private Map<String, String> profileImageMetadata(
        String userId,
        String uploadId,
        long sizeBytes,
        String sha256
    ) {
        return Map.of(
            "stog-user-id", userId,
            "stog-upload-id", uploadId,
            "stog-media-type", PROFILE_IMAGE_MEDIA_TYPE,
            "stog-size", Long.toString(sizeBytes),
            "stog-sha256", sha256
        );
    }

    private Map<String, String> tripCoverImageMetadata(
        String userId,
        long tripId,
        String uploadId,
        long sizeBytes,
        String sha256
    ) {
        return Map.of(
            "stog-user-id", userId,
            "stog-trip-id", Long.toString(tripId),
            "stog-upload-id", uploadId,
            "stog-media-type", TRIP_COVER_IMAGE_MEDIA_TYPE,
            "stog-size", Long.toString(sizeBytes),
            "stog-sha256", sha256
        );
    }

    private void verifyReadableProfileImage(String objectKey, Matcher matcher) {
        GcsObjectClient.ObjectMetadata actual = objects.find(objectKey)
            .orElseThrow(this::invalidProfileImageMetadata);
        String size = Long.toString(actual.sizeBytes());
        Map<String, String> expectedMetadata = profileImageMetadata(
            matcher.group(1),
            matcher.group(2),
            actual.sizeBytes(),
            actual.metadata().get("stog-sha256")
        );
        if (!objectKey.equals(actual.objectKey())
            || !JPEG_CONTENT_TYPE.equals(actual.contentType())
            || actual.sizeBytes() < 1
            || actual.sizeBytes() > properties.originalMaxBytes()
            || !size.equals(actual.metadata().get("stog-size"))
            || !validDigest(actual.metadata().get("stog-sha256"))
            || !expectedMetadata.equals(actual.metadata())) {
            throw invalidProfileImageMetadata();
        }
    }

    private void verifyReadableTripCoverImage(String objectKey, Matcher matcher) {
        GcsObjectClient.ObjectMetadata actual = objects.find(objectKey)
            .orElseThrow(this::invalidTripCoverImageMetadata);
        String size = Long.toString(actual.sizeBytes());
        Map<String, String> expectedMetadata = tripCoverImageMetadata(
            matcher.group(1),
            Long.parseLong(matcher.group(2)),
            matcher.group(3),
            actual.sizeBytes(),
            actual.metadata().get("stog-sha256")
        );
        if (!objectKey.equals(actual.objectKey())
            || !JPEG_CONTENT_TYPE.equals(actual.contentType())
            || actual.sizeBytes() < 1
            || actual.sizeBytes() > properties.originalMaxBytes()
            || !size.equals(actual.metadata().get("stog-size"))
            || !validDigest(actual.metadata().get("stog-sha256"))
            || !expectedMetadata.equals(actual.metadata())) {
            throw invalidTripCoverImageMetadata();
        }
    }

    private String objectKey(
        String userId,
        long tripId,
        String uploadId,
        String derivative
    ) {
        return "photos/accounts/%s/trips/%d/members/%s/uploads/%s/%s.jpg".formatted(
            userId,
            tripId,
            userId,
            uploadId,
            derivative
        );
    }

    private Duration signedUrlTtl() {
        validateConfiguredStorage();
        return properties.signedUrlTtl();
    }

    private void validateUser(String userId) {
        if (!validUser(userId)) {
            throw new IllegalArgumentException("user is invalid");
        }
    }

    private boolean validUser(String userId) {
        return userId != null && userId.matches("[1-9][0-9]*");
    }

    private boolean validDigest(String value) {
        return value != null && value.matches(SHA256_PATTERN);
    }

    private void validateConfiguredStorage() {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("GCS bucket is not configured");
        }
        if (properties.signedUrlTtl() == null || properties.signedUrlTtl().isZero()
            || properties.signedUrlTtl().isNegative()) {
            throw new IllegalStateException("GCS signed URL TTL is not configured");
        }
    }

    private IllegalArgumentException invalidProfileImageMetadata() {
        return new IllegalArgumentException("profile image object metadata is invalid");
    }

    private IllegalArgumentException invalidTripCoverImageMetadata() {
        return new IllegalArgumentException("trip cover image object metadata is invalid");
    }

    private IllegalArgumentException invalidObjectKeys() {
        return new IllegalArgumentException("photo object keys are invalid");
    }

    private IllegalArgumentException invalidObjectMetadata() {
        return new IllegalArgumentException("photo object metadata is invalid");
    }

    public record ProfileImageUpload(
        String objectKey,
        URI uploadUrl,
        String contentType,
        Map<String, String> uploadHeaders,
        Instant expiresAt
    ) {
        public ProfileImageUpload {
            uploadHeaders = Map.copyOf(uploadHeaders);
        }
    }

    public record TripCoverImageUpload(
        String objectKey,
        URI uploadUrl,
        String contentType,
        Map<String, String> uploadHeaders,
        Instant expiresAt
    ) {
        public TripCoverImageUpload {
            uploadHeaders = Map.copyOf(uploadHeaders);
        }
    }

    public record PreparedUpload(
        String userId,
        PhotoRequests.UploadUrl request,
        String originalObjectKey,
        String thumbnailObjectKey,
        Map<String, String> originalMetadata,
        Map<String, String> thumbnailMetadata,
        Duration ttl,
        Instant expiresAt
    ) {
        public PreparedUpload {
            originalMetadata = Map.copyOf(originalMetadata);
            thumbnailMetadata = Map.copyOf(thumbnailMetadata);
        }
    }

    private record ObjectPair(ObjectKey original, ObjectKey thumbnail) {
    }

    private record ObjectKey(
        String objectKey,
        String accountId,
        String tripId,
        String memberId,
        String uploadId,
        boolean thumbnail,
        String contentType,
        boolean legacy
    ) {
        boolean sameUpload(ObjectKey other) {
            return accountId.equals(other.accountId)
                && java.util.Objects.equals(tripId, other.tripId)
                && memberId.equals(other.memberId)
                && uploadId.equals(other.uploadId)
                && legacy == other.legacy;
        }
    }
}
