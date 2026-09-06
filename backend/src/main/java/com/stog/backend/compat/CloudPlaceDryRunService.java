package com.stog.backend.compat;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CloudPlaceDryRunService {
    private final CloudPlaceReadRepository places;
    private final CloudPlaceNormalizer normalizer = new CloudPlaceNormalizer();

    public CloudPlaceDryRunService(CloudPlaceReadRepository places) {
        this.places = places;
    }

    @Transactional(readOnly = true)
    public CloudPlaceDryRunReport previewActive(int limit) {
        CloudPlaceReconciliationResult reconciliation = places.findActive(limit);
        CloudPlaceDryRunReport report = previewFixture(reconciliation.rows());
        return report.withReferenceDiagnostics(
            reconciliation.orphanReferenceCount(),
            reconciliation.duplicateReferenceCount()
        );
    }

    /**
     * Builds a deterministic, read-only import manifest from the legacy source
     * view. It never invokes the canonical writer or any external service.
     */
    @Transactional(readOnly = true)
    public CloudPlaceImportManifest previewImportManifest(int limit) {
        CloudPlaceReconciliationResult reconciliation = places.findActive(limit);
        return manifest(
            reconciliation.rows(),
            reconciliation.duplicateReferenceCount(),
            reconciliation.orphanReferenceCount()
        );
    }

    public CloudPlaceDryRunReport previewFixture(List<CloudPlaceSourceRow> rows) {
        return CloudPlaceDryRunReport.fromNormalizations(normalizer.normalizeAll(rows));
    }

    public CloudPlaceImportManifest previewImportManifestFixture(
        List<CloudPlaceSourceRow> rows
    ) {
        return manifest(rows, 0, 0);
    }

    public CloudPlaceImportManifest previewImportManifestFixture(
        CloudPlaceReconciliationResult reconciliation
    ) {
        return manifest(
            reconciliation.rows(),
            reconciliation.duplicateReferenceCount(),
            reconciliation.orphanReferenceCount()
        );
    }

    private CloudPlaceImportManifest manifest(
        List<CloudPlaceSourceRow> rows,
        long duplicateReferences,
        long orphanReferences
    ) {
        return CloudPlaceImportManifest.from(
            normalizer.normalizeAll(rows),
            duplicateReferences,
            orphanReferences
        );
    }
}
