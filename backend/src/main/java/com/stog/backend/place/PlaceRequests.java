package com.stog.backend.place;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.util.List;

public final class PlaceRequests {
    private PlaceRequests() {
    }

    public record Search(
        @NotBlank String query,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @DecimalMin(value = "0.0", inclusive = false) Double radius_meters,
        @Min(1) Integer max_result_count
    ) {
    }

    public record Center(
        @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double longitude
    ) {
    }

    public record Nearby(
        @NotNull @Valid Center center,
        @DecimalMin("1.0") double radius_meters,
        @NotEmpty List<@NotBlank String> included_types,
        @Min(1) @Max(20) Integer max_result_count
    ) {
        public Nearby {
            included_types = List.copyOf(included_types);
        }
    }
}
