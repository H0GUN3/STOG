package com.stog.backend.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public final class ItineraryRequests {
    private ItineraryRequests() {
    }

    public record Replace(@NotNull List<@Valid Item> items) {
        public Replace {
            items = List.copyOf(items);
        }
    }

    public record Item(
        @NotNull Long basket_item_id,
        @NotNull @Positive Integer day_number,
        @NotNull @PositiveOrZero Integer order_index,
        String planned_arrival,
        @Positive Integer planned_duration_min,
        Boolean is_fixed
    ) {
        public Item {
            is_fixed = Boolean.TRUE.equals(is_fixed);
        }

        public Item(
            Long basketItemId,
            Integer dayNumber,
            Integer orderIndex,
            String plannedArrival,
            Integer plannedDurationMin
        ) {
            this(basketItemId, dayNumber, orderIndex, plannedArrival, plannedDurationMin, false);
        }
    }
}
