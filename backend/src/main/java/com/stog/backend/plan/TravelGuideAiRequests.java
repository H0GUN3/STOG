package com.stog.backend.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import com.stog.backend.trail.TrailRequests;

public final class TravelGuideAiRequests {
    private TravelGuideAiRequests() {
    }

    public record Preview(
        @PositiveOrZero long base_version,
        @NotEmpty List<@Valid DayWindow> day_windows,
        List<@Valid Action> actions,
        TrailRequests.TravelMode travel_mode
    ) {
        public Preview {
            day_windows = List.copyOf(day_windows);
            actions = List.copyOf(actions);
        }

        public Preview(long baseVersion, List<DayWindow> dayWindows, List<Action> actions) {
            this(baseVersion, dayWindows, actions, null);
        }
    }

    public record DayWindow(
        @Positive int day_number,
        @NotBlank String start,
        @NotBlank String end
    ) {
    }

    public record Action(
        @NotNull @Positive Long basket_item_id,
        @NotNull @Positive Integer day_number,
        @NotNull @PositiveOrZero Integer order_index,
        @NotBlank String planned_arrival,
        @NotNull @Positive Integer planned_duration_min,
        @NotNull @PositiveOrZero Integer travel_minutes_from_previous,
        boolean is_fixed
    ) {
    }

    public record Apply(
        @NotNull UUID client_apply_id,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String proposal_fingerprint
    ) {
    }
}
