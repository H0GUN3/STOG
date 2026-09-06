package com.stog.backend.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.events.nearby")
public record EventSearchProperties(
    double defaultRadiusMeters,
    int resultLimit,
    int horizonDays
) {
    public EventSearchProperties {
        if (!Double.isFinite(defaultRadiusMeters) || defaultRadiusMeters <= 0.0) {
            defaultRadiusMeters = 10_000.0;
        }
        if (resultLimit < 1) {
            resultLimit = 10;
        }
        if (horizonDays < 1) {
            horizonDays = 90;
        }
    }
}
