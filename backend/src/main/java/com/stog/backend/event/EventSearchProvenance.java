package com.stog.backend.event;

public record EventSearchProvenance(
    String kind,
    String source
) {
    public static EventSearchProvenance tourApi() {
        return new EventSearchProvenance("canonical", "tour_api");
    }
}
