package com.stog.backend.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("stog.storage")
public record StorageProperties(
    String bucket,
    Duration signedUrlTtl,
    String signerServiceAccount,
    DataSize originalMaxSize,
    DataSize thumbnailMaxSize
) {
    long originalMaxBytes() {
        return requiredPositiveBytes(originalMaxSize, "original-max-size");
    }

    long thumbnailMaxBytes() {
        return requiredPositiveBytes(thumbnailMaxSize, "thumbnail-max-size");
    }

    private long requiredPositiveBytes(DataSize value, String name) {
        if (value == null || value.toBytes() <= 0) {
            throw new IllegalStateException("GCS " + name + " is not configured");
        }
        return value.toBytes();
    }
}
