package com.stog.backend.compat;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment/profile configuration for the approved TourAPI catalog command. */
@ConfigurationProperties("stog.catalog.refresh.tour-api")
public record TourApiCatalogProperties(
    URI url,
    String serviceKey,
    Duration connectTimeout,
    Duration readTimeout
) {
}
