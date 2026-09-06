package com.stog.backend.storage;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FixedGcsObjectClient implements GcsObjectClient {
    private final Map<String, ObjectMetadata> metadata = new HashMap<>();
    private final Map<String, ContentDigest> contentDigests = new HashMap<>();
    private final List<String> signedPutObjectKeys = new ArrayList<>();
    private final List<String> signedReadObjectKeys = new ArrayList<>();

    void put(String objectKey, String contentType, String derivative) {
        put(objectKey, objectKey, contentType, 128L, derivative);
    }

    void put(
        String requestedObjectKey,
        String reportedObjectKey,
        String contentType,
        long sizeBytes,
        String derivative
    ) {
        put(
            requestedObjectKey,
            reportedObjectKey,
            contentType,
            sizeBytes,
            Map.of("stog-derivative", derivative)
        );
    }

    void put(
        String requestedObjectKey,
        String reportedObjectKey,
        String contentType,
        long sizeBytes,
        Map<String, String> objectMetadata
    ) {
        String crc32c = "crc32c-" + Integer.toUnsignedString(requestedObjectKey.hashCode());
        metadata.put(requestedObjectKey, new ObjectMetadata(
            reportedObjectKey,
            contentType,
            sizeBytes,
            crc32c,
            objectMetadata
        ));
        contentDigests.put(requestedObjectKey, new ContentDigest(
            sizeBytes,
            objectMetadata.getOrDefault("stog-sha256", "0".repeat(64)),
            crc32c
        ));
    }

    void setContentDigest(
        String objectKey,
        String sha256,
        String crc32c,
        long sizeBytes
    ) {
        ObjectMetadata current = metadata.get(objectKey);
        metadata.put(objectKey, new ObjectMetadata(
            current.objectKey(),
            current.contentType(),
            current.sizeBytes(),
            crc32c,
            current.metadata()
        ));
        contentDigests.put(objectKey, new ContentDigest(sizeBytes, sha256, crc32c));
    }

    void setProviderCrc32c(String objectKey, String crc32c) {
        ObjectMetadata current = metadata.get(objectKey);
        metadata.put(objectKey, new ObjectMetadata(
            current.objectKey(),
            current.contentType(),
            current.sizeBytes(),
            crc32c,
            current.metadata()
        ));
    }

    void remove(String objectKey) {
        metadata.remove(objectKey);
        contentDigests.remove(objectKey);
    }

    void clear() {
        metadata.clear();
        contentDigests.clear();
        signedPutObjectKeys.clear();
        signedReadObjectKeys.clear();
    }

    void clearSignedReads() {
        signedReadObjectKeys.clear();
    }

    List<String> signedPutObjectKeys() {
        return List.copyOf(signedPutObjectKeys);
    }

    List<String> signedReadObjectKeys() {
        return List.copyOf(signedReadObjectKeys);
    }

    @Override
    public URI signPut(
        String objectKey,
        String contentType,
        String sha256,
        Map<String, String> objectMetadata,
        Duration ttl
    ) {
        signedPutObjectKeys.add(objectKey);
        return URI.create("https://storage.test/upload/" + objectKey);
    }

    @Override
    public URI signRead(String objectKey, Duration ttl) {
        signedReadObjectKeys.add(objectKey);
        return URI.create("https://storage.test/read/" + objectKey);
    }

    @Override
    public Optional<ObjectMetadata> find(String objectKey) {
        return Optional.ofNullable(metadata.get(objectKey));
    }

    @Override
    public ContentDigest digest(String objectKey, long maxBytes) {
        ContentDigest digest = contentDigests.get(objectKey);
        if (digest == null) {
            throw new IllegalArgumentException("object content is unavailable");
        }
        if (digest.sizeBytes() > maxBytes) {
            throw new IllegalArgumentException("object content exceeds configured size");
        }
        return digest;
    }
}
