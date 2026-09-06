package com.stog.backend.compat;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.catalog.refresh.tour-photo")
public record TourPhotoGalleryProperties(
    URI url,
    String serviceKey,
    int pageSize,
    int maxPlaces,
    Duration connectTimeout,
    Duration readTimeout
) {
    public TourPhotoGalleryProperties {
        pageSize = pageSize < 1 ? 10 : Math.min(pageSize, 100);
        maxPlaces = maxPlaces < 1 ? 1 : Math.min(maxPlaces, 1_000);
        connectTimeout = connectTimeout == null
            ? Duration.ofSeconds(3)
            : connectTimeout;
        readTimeout = readTimeout == null
            ? Duration.ofSeconds(10)
            : readTimeout;
    }
}
