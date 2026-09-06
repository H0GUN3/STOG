package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CloudPlaceImportManifestRow(
    @JsonProperty("source_travel_item_id") String sourceTravelItemId,
    @JsonProperty("classification") String classification,
    @JsonProperty("reason") String reason,
    @JsonProperty("source") CloudPlaceSourceRow source,
    @JsonProperty("canonical") CloudPlaceCanonicalRecord canonical,
    @JsonProperty("field_dispositions") List<CloudPlaceFieldDisposition> fieldDispositions,
    @JsonProperty("diagnostics") List<String> diagnostics,
    @JsonProperty("row_digest") String rowDigest
) {
    public CloudPlaceImportManifestRow {
        fieldDispositions = List.copyOf(fieldDispositions);
        diagnostics = List.copyOf(diagnostics);
    }
}
