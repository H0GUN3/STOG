package com.stog.backend.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class TripCoverRequests {
    private TripCoverRequests() {
    }

    public record Upload(
        @NotBlank String client_upload_id,
        @NotBlank String content_type,
        @NotNull @Positive Long size_bytes,
        @NotBlank String sha256
    ) {
    }
}
