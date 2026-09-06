package com.stog.backend.place.search;

import java.util.List;

public record PlaceSearchResponse(List<PlaceSearchResult> candidates) {
    public PlaceSearchResponse {
        candidates = List.copyOf(candidates);
    }
}
