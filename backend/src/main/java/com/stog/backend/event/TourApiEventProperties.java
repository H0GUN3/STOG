package com.stog.backend.event;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.events.refresh")
public record TourApiEventProperties(
    URI url,
    String serviceKey,
    List<Integer> areaCodes,
    int horizonDays,
    int pageSize,
    Duration connectTimeout,
    Duration readTimeout
) {
    public TourApiEventProperties {
        areaCodes = areaCodes == null ? List.of() : List.copyOf(areaCodes);
        horizonDays = horizonDays < 1 ? 90 : horizonDays;
        pageSize = pageSize < 1 ? 100 : Math.min(pageSize, 1_000);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
