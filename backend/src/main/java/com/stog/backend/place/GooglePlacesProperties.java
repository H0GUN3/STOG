package com.stog.backend.place;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.google.places")
public record GooglePlacesProperties(
    String apiKey,
    URI baseUrl,
    String defaultLanguageCode,
    String defaultRegionCode
) {
}
