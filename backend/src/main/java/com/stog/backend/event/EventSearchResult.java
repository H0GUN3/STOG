package com.stog.backend.event;

import java.time.LocalDate;

public record EventSearchResult(
    String provider,
    String external_id,
    String title,
    String venue_name,
    String formatted_address,
    Double latitude,
    Double longitude,
    LocalDate starts_on,
    LocalDate ends_on,
    String detail_uri,
    String image_uri,
    EventSearchProvenance provenance
) {
}
