package com.stog.backend.trail;

public record TrailResult(
    String duration,
    Long distance_meters,
    String encoded_polyline
) {
}
