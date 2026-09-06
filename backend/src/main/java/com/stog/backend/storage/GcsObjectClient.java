package com.stog.backend.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface GcsObjectClient {
    URI signPut(
        String objectKey,
        String contentType,
        String sha256,
        Map<String, String> metadata,
        Duration ttl
    );

    URI signRead(String objectKey, Duration ttl);

    Optional<ObjectMetadata> find(String objectKey);

    ContentDigest digest(String objectKey, long maxBytes);

    record ObjectMetadata(
        String objectKey,
        String contentType,
        long sizeBytes,
        String crc32c,
        Map<String, String> metadata
    ) {
        public ObjectMetadata {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record ContentDigest(
        long sizeBytes,
        String sha256,
        String crc32c
    ) {
    }
}
