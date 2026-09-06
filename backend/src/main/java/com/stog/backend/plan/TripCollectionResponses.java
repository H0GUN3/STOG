package com.stog.backend.plan;

import java.time.Instant;

public final class TripCollectionResponses {
    private TripCollectionResponses() {
    }

    public record State(
        long trip_id,
        long user_id,
        String collector_state,
        String permission_state,
        String sync_cursor,
        long mode_version,
        Instant collector_started_at,
        Instant updated_at
    ) {
    }
}
