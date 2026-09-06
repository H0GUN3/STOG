package com.stog.backend.plan;

import java.time.Duration;
import java.time.Instant;

public final class VisitDecision {
    private VisitDecision() {
    }

    public static Result classify(
        Instant enteredAt,
        Instant leftAt,
        boolean isInterpolated,
        Rules rules
    ) {
        Duration dwell = duration(enteredAt, leftAt);
        if (isInterpolated) {
            return new Result(Status.PASSED, dwell, false);
        }
        return new Result(
            dwell.compareTo(rules.visitDwell()) >= 0 ? Status.VISITED : Status.PASSED,
            dwell,
            dwell.compareTo(rules.visitDwell()) >= 0
        );
    }

    public static boolean isInterpolated(
        double distanceMeters,
        Instant enteredAt,
        Instant leftAt,
        Rules rules
    ) {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0.0) {
            throw new IllegalArgumentException("distanceMeters is invalid");
        }
        Duration elapsed = duration(enteredAt, leftAt);
        if (elapsed.isZero()) {
            return distanceMeters > 0.0;
        }
        double kilometersPerHour = distanceMeters / 1000.0
            / (elapsed.toMillis() / 3_600_000.0);
        return kilometersPerHour > rules.walkSpeedLimitKmh();
    }

    private static Duration duration(Instant enteredAt, Instant leftAt) {
        if (enteredAt == null || leftAt == null || leftAt.isBefore(enteredAt)) {
            throw new IllegalArgumentException("visit timestamps are invalid");
        }
        return Duration.between(enteredAt, leftAt);
    }

    public enum Status {
        PASSED("passed"),
        VISITED("visited");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public record Rules(Duration visitDwell, double walkSpeedLimitKmh) {
        public Rules {
            if (visitDwell == null || visitDwell.isNegative() || visitDwell.isZero()) {
                throw new IllegalArgumentException("visitDwell must be positive");
            }
            if (!Double.isFinite(walkSpeedLimitKmh) || walkSpeedLimitKmh <= 0.0) {
                throw new IllegalArgumentException("walkSpeedLimitKmh must be positive");
            }
        }
    }

    public record Result(Status status, Duration dwell, boolean rewardEligible) {
    }
}
