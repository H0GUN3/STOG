package com.stog.backend.event;

import com.stog.backend.place.PlaceRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public final class EventRequests {
    private EventRequests() {
    }

    public record Nearby(
        @NotNull @Valid PlaceRequests.Center center,
        @DecimalMin("1.0") Double radius_meters,
        LocalDate from_date,
        LocalDate to_date,
        @Min(1) @Max(20) Integer max_result_count
    ) {
    }
}
