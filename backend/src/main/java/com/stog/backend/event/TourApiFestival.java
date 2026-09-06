package com.stog.backend.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;

record TourApiFestival(
    String externalId,
    String title,
    String venueName,
    String formattedAddress,
    double latitude,
    double longitude,
    LocalDate startsOn,
    LocalDate endsOn,
    String detailUri,
    String imageUri,
    OffsetDateTime sourceUpdatedAt
) {
    String provider() {
        return "tour_api";
    }
}
