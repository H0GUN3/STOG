package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicitly records the fate of every observed source field. */
public record CloudPlaceFieldDisposition(
    @JsonProperty("source_field") String sourceField,
    @JsonProperty("disposition") String disposition,
    @JsonProperty("canonical_target") String canonicalTarget,
    @JsonProperty("reason") String reason
) {
}
