package com.stog.backend.event;

import java.util.List;

public record EventSearchResponse(List<EventSearchResult> events) {
    public EventSearchResponse {
        events = List.copyOf(events);
    }
}
