package com.stog.backend.plan;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public final class VisitRequests {
    private VisitRequests() {
    }

    public record Record(
        @NotNull UUID client_visit_id,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payload_fingerprint,
        @NotBlank @Pattern(regexp = "^[0-9a-f]+$") String cell_id,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
        @NotNull Instant entered_at,
        @NotNull Instant left_at,
        @NotBlank @Pattern(regexp = "^(passed|visited)$") String status,
        boolean is_interpolated
    ) {
    }
}
