package com.stog.backend.compat;

import java.util.List;

/** One complete public-data response prepared for the task-8 manifest builder. */
public record PublicCatalogSnapshot(
    String source,
    String licenseDecision,
    boolean complete,
    List<CloudPlaceSourceRow> rows
) {
    public PublicCatalogSnapshot {
        rows = rows == null ? null : List.copyOf(rows);
    }
}
