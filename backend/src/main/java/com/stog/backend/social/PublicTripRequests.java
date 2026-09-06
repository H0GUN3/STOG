package com.stog.backend.social;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class PublicTripRequests {
    private PublicTripRequests() {
    }

    public record Copy(
        @Positive long destination_trip_id,
        @Positive int destination_day,
        @NotBlank @Size(max = 200) String idempotency_key
    ) {
    }
}
