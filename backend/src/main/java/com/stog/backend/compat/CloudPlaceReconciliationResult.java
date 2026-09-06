package com.stog.backend.compat;

import java.util.List;

public record CloudPlaceReconciliationResult(
    int inputRowCount,
    List<CloudPlaceSourceRow> rows,
    long duplicateReferenceCount,
    long orphanReferenceCount
) {
    public CloudPlaceReconciliationResult {
        rows = List.copyOf(rows);
    }

    public int resultRowCount() {
        return rows.size();
    }
}
