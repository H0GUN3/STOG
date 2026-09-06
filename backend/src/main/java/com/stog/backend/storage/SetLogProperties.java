package com.stog.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.set-log")
public record SetLogProperties(
    int captionMaxCodePoints,
    double locationMaxAccuracyMeters,
    double placeRadiusMeters,
    double placeTieDeltaMeters
) {
}
