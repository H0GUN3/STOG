package com.stog.backend.plan;

import java.time.Instant;
import java.util.List;

public final class ItineraryResponses {
    private ItineraryResponses() {
    }

    public record Items(long version, List<Item> items) {
        public Items {
            items = List.copyOf(items);
        }

        public Items(List<Item> items) {
            this(0, items);
        }
    }

    public record Item(
        long basket_item_id,
        Long place_id,
        String title,
        String category,
        Double lat,
        Double lng,
        String address,
        String image_url,
        int day_number,
        int order_index,
        String planned_arrival,
        Integer planned_duration_min,
        boolean is_fixed
    ) {
        public Item(
            long basketItemId,
            int dayNumber,
            int orderIndex,
            String plannedArrival,
            Integer plannedDurationMin,
            boolean fixed
        ) {
            this(
                basketItemId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dayNumber,
                orderIndex,
                plannedArrival,
                plannedDurationMin,
                fixed
            );
        }
    }

    public record Changes(List<Change> changes) {
        public Changes {
            changes = List.copyOf(changes);
        }
    }

    public record Change(
        long id,
        long user_id,
        String action,
        String payload,
        Instant created_at
    ) {
    }
}
