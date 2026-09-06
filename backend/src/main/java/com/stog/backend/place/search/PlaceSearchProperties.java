package com.stog.backend.place.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.catalog.search")
public record PlaceSearchProperties(
    int resultLimit,
    int fallbackMinResults,
    double fuzzyThreshold,
    double defaultRadiusMeters,
    double nearbyDuplicateDistanceMeters
) {
    public PlaceSearchProperties {
        if (resultLimit < 1) {
            throw new IllegalArgumentException("resultLimit must be positive");
        }
        if (fallbackMinResults < 1 || fallbackMinResults > resultLimit) {
            throw new IllegalArgumentException(
                "fallbackMinResults must be between one and resultLimit"
            );
        }
        if (!Double.isFinite(fuzzyThreshold)
            || fuzzyThreshold < 0.0
            || fuzzyThreshold > 1.0) {
            throw new IllegalArgumentException(
                "fuzzyThreshold must be between zero and one"
            );
        }
        if (!Double.isFinite(defaultRadiusMeters) || defaultRadiusMeters <= 0.0) {
            throw new IllegalArgumentException("defaultRadiusMeters must be positive");
        }
        if (!Double.isFinite(nearbyDuplicateDistanceMeters)
            || nearbyDuplicateDistanceMeters <= 0.0) {
            throw new IllegalArgumentException(
                "nearbyDuplicateDistanceMeters must be positive"
            );
        }
    }
}
