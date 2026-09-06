package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CloudPlaceExternalRef(
    @JsonProperty("provider") String provider,
    @JsonProperty("external_id") String externalId
) {
}
