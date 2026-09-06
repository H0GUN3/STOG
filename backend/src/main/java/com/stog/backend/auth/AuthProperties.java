package com.stog.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.auth")
public record AuthProperties(
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration naverTicketTtl,
    int randomTokenBytes
) {
}
