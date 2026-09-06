package com.stog.backend.compat;

import com.stog.backend.place.PlaceCandidate;

public record CloudPlaceMapping(
    String travelItemId,
    PlaceCandidate candidate,
    String source,
    String sourceItemId,
    String legacyCellId,
    String legacyH3Index,
    Integer legacyH3Resolution
) {
}
