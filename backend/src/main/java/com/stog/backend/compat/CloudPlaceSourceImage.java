package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A TourAPI image candidate with an explicit per-image reuse decision.
 * The importer still requires the parent source license to allow {@code image}.
 */
public record CloudPlaceSourceImage(
    @JsonProperty("source_image_id") String sourceImageId,
    @JsonProperty("source_url") String sourceUrl,
    @JsonProperty("source_digest") String sourceDigest,
    @JsonProperty("reusable") Boolean reusable
) {
}
