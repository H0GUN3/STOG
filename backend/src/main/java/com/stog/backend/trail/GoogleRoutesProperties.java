package com.stog.backend.trail;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.google.routes")
public record GoogleRoutesProperties(
    String apiKey,
    URI baseUrl
) {
}
