package com.stog.backend.plan;

import java.time.Instant;

public final class BasketResponses {
    private BasketResponses() {
    }

    public record Added(
        long id,
        long place_id,
        String cell_id,
        String status
    ) {
    }

    public record LinkAdded(long id, String status) {
    }

    public record Item(
        long id,
        String item_type,
        String title,
        String category,
        String source,
        String status,
        String image_url,
        Instant added_at
    ) {
    }
}
