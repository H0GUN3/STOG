package com.stog.backend.plan;

import java.time.Instant;
import java.util.UUID;

public final class VisitResponses {
    private VisitResponses() {
    }

    public record Recorded(
        long id,
        UUID client_visit_id,
        String payload_fingerprint,
        String cell_id,
        String status,
        boolean is_interpolated,
        boolean review_required,
        Instant created_at
    ) {
    }
}
