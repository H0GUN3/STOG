package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record CloudPlaceImportManifestSummary(
    @JsonProperty("source_row_count") int sourceRowCount,
    @JsonProperty("classification_counts") Map<String, Long> classificationCounts,
    @JsonProperty("external_reference_count") long externalReferenceCount,
    @JsonProperty("duplicate_external_reference_count") long duplicateExternalReferenceCount,
    @JsonProperty("orphan_external_reference_count") long orphanExternalReferenceCount,
    @JsonProperty("source_snapshot_digest") String sourceSnapshotDigest,
    @JsonProperty("row_digest") String rowDigest,
    @JsonProperty("diagnostics_digest") String diagnosticsDigest
) {
    public CloudPlaceImportManifestSummary {
        classificationCounts = Map.copyOf(classificationCounts);
    }
}
