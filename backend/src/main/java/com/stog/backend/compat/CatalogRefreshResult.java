package com.stog.backend.compat;

/** Observable outcome of one catalog refresh command invocation. */
public record CatalogRefreshResult(
    String runId,
    Status status,
    String sourceSnapshotDigest,
    CanonicalPlaceImportResult importResult,
    String failureCode
) {
    public enum Status {
        SUCCEEDED,
        NO_OP,
        FAILED
    }

    static CatalogRefreshResult succeeded(
        String runId,
        String sourceSnapshotDigest,
        CanonicalPlaceImportResult importResult
    ) {
        return new CatalogRefreshResult(
            runId,
            importResult.noOp() ? Status.NO_OP : Status.SUCCEEDED,
            sourceSnapshotDigest,
            importResult,
            null
        );
    }

    static CatalogRefreshResult failed(String runId, String failureCode) {
        return new CatalogRefreshResult(runId, Status.FAILED, null, null, failureCode);
    }
}
