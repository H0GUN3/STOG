package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcsSignedUrlServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final StorageProperties PROPERTIES = new StorageProperties(
        "stog-test-media",
        Duration.ofMinutes(5),
        "",
        org.springframework.util.unit.DataSize.ofMegabytes(2),
        org.springframework.util.unit.DataSize.ofKilobytes(50)
    );
    private static final String UPLOAD_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ORIGINAL_DIGEST = "a".repeat(64);
    private static final String THUMBNAIL_DIGEST = "b".repeat(64);
    private static final long ORIGINAL_MAX_BYTES = 2L * 1024L * 1024L;
    private static final long THUMBNAIL_MAX_BYTES = 50L * 1024L;

    @Test
    void signsExactlyTwoDeterministicDirectUploadsWithBoundPolicyHeaders() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);

        PhotoResponses.UploadUrl response = service.issueUploadUrl("42", request());

        assertThat(response.original_object_key()).isEqualTo(originalKey());
        assertThat(response.thumbnail_object_key()).isEqualTo(thumbnailKey());
        assertThat(objects.signedPutObjectKeys()).containsExactly(originalKey(), thumbnailKey());
        assertThat(response.original_upload_headers()).containsAllEntriesOf(Map.of(
            "Content-Type", "image/jpeg",
            "x-goog-content-sha256", ORIGINAL_DIGEST,
            "x-goog-meta-stog-account-id", "42",
            "x-goog-meta-stog-trip-id", "77",
            "x-goog-meta-stog-member-id", "42",
            "x-goog-meta-stog-upload-id", UPLOAD_ID,
            "x-goog-meta-stog-derivative", "normalized-original",
            "x-goog-meta-stog-size", "2048",
            "x-goog-meta-stog-sha256", ORIGINAL_DIGEST
        ));
        assertThat(response.thumbnail_upload_headers()).containsAllEntriesOf(Map.of(
            "Content-Type", "image/jpeg",
            "x-goog-content-sha256", THUMBNAIL_DIGEST,
            "x-goog-meta-stog-derivative", "thumbnail",
            "x-goog-meta-stog-size", "512",
            "x-goog-meta-stog-sha256", THUMBNAIL_DIGEST
        ));
        assertThat(response.expires_at()).isEqualTo(NOW.plusSeconds(300));

        PhotoResponses.UploadUrl replay = service.issueUploadUrl("42", request());
        assertThat(replay.original_object_key()).isEqualTo(response.original_object_key());
        assertThat(replay.thumbnail_object_key()).isEqualTo(response.thumbnail_object_key());
    }

    @Test
    void enforcesConfiguredDerivativeBoundariesBeforeSigningAndVerification() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        PhotoRequests.UploadUrl exact = new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            object("image/jpeg", ORIGINAL_MAX_BYTES, ORIGINAL_DIGEST),
            object("image/jpeg", THUMBNAIL_MAX_BYTES, THUMBNAIL_DIGEST)
        );
        assertThat(service.issueUploadUrl("42", exact).original_object_key()).isNotBlank();

        assertThatThrownBy(() -> service.issueUploadUrl(
            "42",
            new PhotoRequests.UploadUrl(
                77L,
                UPLOAD_ID,
                object("image/jpeg", ORIGINAL_MAX_BYTES + 1L, ORIGINAL_DIGEST),
                exact.thumbnail()
            )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
        assertThatThrownBy(() -> service.issueUploadUrl(
            "42",
            new PhotoRequests.UploadUrl(
                77L,
                UPLOAD_ID,
                exact.original(),
                object("image/jpeg", THUMBNAIL_MAX_BYTES + 1L, THUMBNAIL_DIGEST)
            )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
        PhotoRequests.UploadUrl overflow = new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            object("image/jpeg", Long.MAX_VALUE, ORIGINAL_DIGEST),
            object("image/jpeg", Long.MAX_VALUE, THUMBNAIL_DIGEST)
        );
        assertThatThrownBy(() -> service.issueUploadUrl("42", overflow))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("size_bytes");

        putExpected(objects, originalKey(), exact.original(), "normalized-original");
        putExpected(objects, thumbnailKey(), exact.thumbnail(), "thumbnail");
        service.verifyUploadedObjects("42", exact, originalKey(), thumbnailKey());
        PhotoRequests.UploadUrl originalPlusOne = new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            object("image/jpeg", ORIGINAL_MAX_BYTES + 1L, ORIGINAL_DIGEST),
            exact.thumbnail()
        );
        PhotoRequests.UploadUrl thumbnailPlusOne = new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            exact.original(),
            object("image/jpeg", THUMBNAIL_MAX_BYTES + 1L, THUMBNAIL_DIGEST)
        );
        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42", originalPlusOne, originalKey(), thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42", thumbnailPlusOne, originalKey(), thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42", overflow, originalKey(), thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
    }

    @Test
    void rejectsUnsupportedTypeInvalidDigestAndUnsafeIdentityBeforeSigning() {
        GcsSignedUrlService service = service(new FixedGcsObjectClient());

        assertThatThrownBy(() -> service.issueUploadUrl(
            "42",
            new PhotoRequests.UploadUrl(
                77L,
                UPLOAD_ID,
                new PhotoRequests.UploadObject("image/png", 2048L, ORIGINAL_DIGEST),
                object("image/jpeg", 512L, THUMBNAIL_DIGEST)
            )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content_type");
        assertThatThrownBy(() -> service.issueUploadUrl(
            "42",
            new PhotoRequests.UploadUrl(
                77L,
                UPLOAD_ID,
                object("image/jpeg", 2048L, "not-a-digest"),
                object("image/jpeg", 512L, THUMBNAIL_DIGEST)
            )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sha256");
        assertThatThrownBy(() -> service.issueUploadUrl("42/other", request()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user");
    }

    @Test
    void verifiesBothObjectsAgainstExactPathTypeSizeDigestAndMetadata() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        PhotoRequests.UploadUrl request = request();
        putExpected(objects, originalKey(), request.original(), "normalized-original");

        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42",
            request,
            originalKey(),
            thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metadata");

        putExpected(objects, thumbnailKey(), request.thumbnail(), "thumbnail");
        service.verifyUploadedObjects("42", request, originalKey(), thumbnailKey());
    }

    @Test
    void rejectsForgedShaMetadataWhenIndependentObjectBytesHaveAnotherDigest() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        PhotoRequests.UploadUrl request = request();
        putExpected(objects, originalKey(), request.original(), "normalized-original");
        putExpected(objects, thumbnailKey(), request.thumbnail(), "thumbnail");
        objects.setContentDigest(
            originalKey(),
            "c".repeat(64),
            "provider-crc-original",
            request.original().size_bytes()
        );

        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42", request, originalKey(), thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
    }

    @Test
    void rejectsProviderChecksumThatDisagreesWithTheBoundedContentStream() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        PhotoRequests.UploadUrl request = request();
        putExpected(objects, originalKey(), request.original(), "normalized-original");
        putExpected(objects, thumbnailKey(), request.thumbnail(), "thumbnail");
        objects.setProviderCrc32c(originalKey(), "wrong-provider-crc32c");

        assertThatThrownBy(() -> service.verifyUploadedObjects(
            "42", request, originalKey(), thumbnailKey()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
    }

    @Test
    void rejectsSwappedTraversalWrongOwnerTripTypeSizeAndDigest() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        PhotoRequests.UploadUrl request = request();
        putExpected(objects, originalKey(), request.original(), "normalized-original");
        putExpected(objects, thumbnailKey(), request.thumbnail(), "thumbnail");

        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, thumbnailKey(), originalKey()
        ));
        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, originalKey().replace("/trips/77/", "/trips/78/"), thumbnailKey()
        ));
        assertInvalid(() -> service.verifyUploadedObjects(
            "7", request, originalKey(), thumbnailKey()
        ));
        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, "photos/accounts/42/trips/77/members/42/uploads/../original.jpg", thumbnailKey()
        ));

        objects.put(
            originalKey(),
            originalKey(),
            "image/png",
            2048L,
            expectedMetadata(request, request.original(), "normalized-original")
        );
        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, originalKey(), thumbnailKey()
        ));
        objects.put(
            originalKey(),
            originalKey(),
            "image/jpeg",
            2049L,
            expectedMetadata(request, request.original(), "normalized-original")
        );
        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, originalKey(), thumbnailKey()
        ));
        Map<String, String> wrongDigest = new java.util.HashMap<>(
            expectedMetadata(request, request.original(), "normalized-original")
        );
        wrongDigest.put("stog-sha256", "c".repeat(64));
        objects.put(originalKey(), originalKey(), "image/jpeg", 2048L, wrongDigest);
        assertInvalid(() -> service.verifyUploadedObjects(
            "42", request, originalKey(), thumbnailKey()
        ));
    }

    @Test
    void profileImageUploadsUseUserScopedKeysAndRequireExactObjectMetadata() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        String profileUploadId = "22222222-2222-2222-2222-222222222222";
        String digest = "c".repeat(64);

        GcsSignedUrlService.ProfileImageUpload upload = service.issueProfileImageUpload(
            "42", profileUploadId, "image/jpeg", 2048L, digest
        );

        assertThat(upload.objectKey()).isEqualTo(
            "profile-images/users/42/uploads/" + profileUploadId + ".jpg"
        );
        assertThat(upload.uploadHeaders()).containsAllEntriesOf(Map.of(
            "Content-Type", "image/jpeg",
            "x-goog-content-sha256", digest,
            "x-goog-meta-stog-user-id", "42",
            "x-goog-meta-stog-upload-id", profileUploadId,
            "x-goog-meta-stog-media-type", "profile-image",
            "x-goog-meta-stog-size", "2048",
            "x-goog-meta-stog-sha256", digest
        ));
        objects.put(
            upload.objectKey(), upload.objectKey(), "image/jpeg", 2048L,
            Map.of(
                "stog-user-id", "42",
                "stog-upload-id", profileUploadId,
                "stog-media-type", "profile-image",
                "stog-size", "2048",
                "stog-sha256", digest
            )
        );
        assertThat(service.verifyProfileImageUpload(
            "42", profileUploadId, "image/jpeg", 2048L, digest
        )).isEqualTo(upload.objectKey());
        assertThat(service.issueReadUrl(upload.objectKey())).isEqualTo(
            java.net.URI.create("https://storage.test/read/" + upload.objectKey())
        );

        objects.put(
            upload.objectKey(), upload.objectKey(), "image/jpeg", 2048L,
            Map.of(
                "stog-user-id", "7",
                "stog-upload-id", profileUploadId,
                "stog-media-type", "profile-image",
                "stog-size", "2048",
                "stog-sha256", digest
            )
        );
        assertInvalid(() -> service.verifyProfileImageUpload(
            "42", profileUploadId, "image/jpeg", 2048L, digest
        ));
        assertThatThrownBy(() -> service.issueProfileImageUpload(
            "42/other", profileUploadId, "image/jpeg", 2048L, digest
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user");
        assertThatThrownBy(() -> service.issueProfileImageUpload(
            "42", profileUploadId, "image/png", 2048L, digest
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content_type");
        assertThatThrownBy(() -> service.issueProfileImageUpload(
            "42", profileUploadId, "image/jpeg", ORIGINAL_MAX_BYTES + 1, digest
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size_bytes");
    }

    @Test
    void signsReadsOnlyAfterStoredObjectsValidateAndNeverSynthesizesPublicUrls() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);
        putExpected(objects, originalKey(), request().original(), "normalized-original");
        putExpected(objects, thumbnailKey(), request().thumbnail(), "thumbnail");

        PhotoResponses.ReadUrls urls = service.issueReadUrls(originalKey(), thumbnailKey());

        assertThat(urls.original_url().toString()).isEqualTo(
            "https://storage.test/read/" + originalKey()
        );
        assertThat(urls.thumbnail_url().toString()).isEqualTo(
            "https://storage.test/read/" + thumbnailKey()
        );
        assertThat(objects.signedReadObjectKeys()).containsExactly(originalKey(), thumbnailKey());
    }

    @Test
    void signsCatalogImageReadsForValidatedCatalogObjectKeys() {
        FixedGcsObjectClient objects = new FixedGcsObjectClient();
        GcsSignedUrlService service = service(objects);

        assertThat(service.issueReadUrl("catalog-images/v1/source-images/123")).isEqualTo(
            java.net.URI.create(
                "https://storage.test/read/catalog-images/v1/source-images/123"
            )
        );
        assertThat(objects.signedReadObjectKeys()).containsExactly(
            "catalog-images/v1/source-images/123"
        );
    }

    private PhotoRequests.UploadUrl request() {
        return new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            object("image/jpeg", 2048L, ORIGINAL_DIGEST),
            object("image/jpeg", 512L, THUMBNAIL_DIGEST)
        );
    }

    private PhotoRequests.UploadObject object(String type, long size, String digest) {
        return new PhotoRequests.UploadObject(type, size, digest);
    }

    private String originalKey() {
        return "photos/accounts/42/trips/77/members/42/uploads/" + UPLOAD_ID
            + "/original.jpg";
    }

    private String thumbnailKey() {
        return "photos/accounts/42/trips/77/members/42/uploads/" + UPLOAD_ID
            + "/thumbnail.jpg";
    }

    private void putExpected(
        FixedGcsObjectClient objects,
        String key,
        PhotoRequests.UploadObject object,
        String derivative
    ) {
        objects.put(
            key,
            key,
            object.content_type(),
            object.size_bytes(),
            expectedMetadata(request(), object, derivative)
        );
    }

    private Map<String, String> expectedMetadata(
        PhotoRequests.UploadUrl request,
        PhotoRequests.UploadObject object,
        String derivative
    ) {
        return Map.of(
            "stog-account-id", "42",
            "stog-trip-id", request.trip_id().toString(),
            "stog-member-id", "42",
            "stog-upload-id", request.client_upload_id(),
            "stog-derivative", derivative,
            "stog-size", object.size_bytes().toString(),
            "stog-sha256", object.sha256()
        );
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(IllegalArgumentException.class);
    }

    private GcsSignedUrlService service(FixedGcsObjectClient objects) {
        return new GcsSignedUrlService(
            objects,
            PROPERTIES,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
