package com.stog.backend.compat;

/** Reconciled outcome of one local canonical manifest import attempt. */
public record CanonicalPlaceImportResult(
    String manifestDigest,
    long sourceRowCount,
    long importableCount,
    long quarantinedCount,
    long preservedOnlyCount,
    long createdCount,
    long updatedCount,
    long noOpCount,
    boolean noOp
) {
    public CanonicalPlaceImportResult {
        if (sourceRowCount != importableCount + quarantinedCount + preservedOnlyCount) {
            throw new IllegalArgumentException("classification counts do not reconcile");
        }
    }
}
