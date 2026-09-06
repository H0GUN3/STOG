package com.stog.backend.trail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class TrailRequests {
    private TrailRequests() {
    }

    public enum TravelMode {
        WALK,
        TRANSIT
    }

    public record Coordinate(
        @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double longitude
    ) {
    }

    public record Compute(
        @NotNull @Valid Coordinate origin,
        @NotNull @Valid Coordinate destination,
        List<@Valid Coordinate> intermediates,
        @NotNull TravelMode travel_mode
    ) {
        public Compute {
            intermediates = List.copyOf(
                intermediates == null ? List.of() : intermediates
            );
        }
    }
}
