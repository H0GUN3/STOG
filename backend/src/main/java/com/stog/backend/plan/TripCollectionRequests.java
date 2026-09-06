package com.stog.backend.plan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class TripCollectionRequests {
    private TripCollectionRequests() {
    }

    public record Update(
        @NotBlank
        @Pattern(regexp = "^(inactive|starting|active|dormant|blocked|ended)$")
        String collector_state,
        @NotBlank
        @Pattern(regexp = "^(unknown|granted|denied|revoked)$")
        String permission_state,
        String sync_cursor,
        @Min(0) long expected_mode_version
    ) {
    }
}
