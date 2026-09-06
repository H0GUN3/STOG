package com.stog.backend.compat;

import com.stog.backend.place.PlaceCandidate;
import java.util.Objects;

public record CloudPlaceUpsertCommand(
    PlaceCandidate candidate,
    String itemType,
    String source,
    String environmentType,
    Integer estimatedCost
) {
    public CloudPlaceUpsertCommand {
        Objects.requireNonNull(candidate, "candidate is required");
        if (!"google".equals(candidate.provider())) {
            throw new IllegalArgumentException("candidate provider must be google");
        }
        if (candidate.external_id() == null || candidate.external_id().isBlank()) {
            throw new IllegalArgumentException("candidate external_id is required");
        }
        if (candidate.name() == null || candidate.name().isBlank()) {
            throw new IllegalArgumentException("candidate name is required");
        }
        if (!"BUSINESS".equals(itemType) && !"TOURIST_PLACE".equals(itemType)) {
            throw new IllegalArgumentException("itemType is unsupported");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (!"INDOOR".equals(environmentType)
            && !"OUTDOOR".equals(environmentType)
            && !"MIXED".equals(environmentType)
            && !"UNKNOWN".equals(environmentType)) {
            throw new IllegalArgumentException("environmentType is unsupported");
        }
        if (estimatedCost != null && estimatedCost < 0) {
            throw new IllegalArgumentException("estimatedCost must not be negative");
        }
    }
}
