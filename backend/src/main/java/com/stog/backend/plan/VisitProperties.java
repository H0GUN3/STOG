package com.stog.backend.plan;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.visit")
public record VisitProperties(
    Duration dwell,
    double walkSpeedLimitKmh,
    Duration lateGrace
) {
    public VisitProperties {
        if (dwell == null || dwell.isNegative() || dwell.isZero()) {
            throw new IllegalArgumentException("dwell must be positive");
        }
        if (!Double.isFinite(walkSpeedLimitKmh) || walkSpeedLimitKmh <= 0.0) {
            throw new IllegalArgumentException("walkSpeedLimitKmh must be positive");
        }
        if (lateGrace == null || lateGrace.isNegative() || lateGrace.isZero()) {
            throw new IllegalArgumentException("lateGrace must be positive");
        }
    }

    public VisitDecision.Rules rules() {
        return new VisitDecision.Rules(dwell, walkSpeedLimitKmh);
    }
}
