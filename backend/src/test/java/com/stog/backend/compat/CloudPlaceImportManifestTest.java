package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.cell.CellIdCalculator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CloudPlaceImportManifestTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CloudPlaceDryRunService dryRun = new CloudPlaceDryRunService(null);

    @Test
    void producesACompleteDeterministicReadOnlyManifestForEveryFixtureSourceRow() throws Exception {
        Path fixturePath = Path.of("src/test/resources/compat/cloud-place-fixture.json");
        byte[] before = Files.readAllBytes(fixturePath);
        List<CloudPlaceSourceRow> rows = fixtureRows();

        CloudPlaceImportManifest first = dryRun.previewImportManifestFixture(rows);
        List<CloudPlaceSourceRow> reversed = new ArrayList<>(rows);
        java.util.Collections.reverse(reversed);
        CloudPlaceImportManifest repeated = dryRun.previewImportManifestFixture(reversed);

        assertThat(first.toJson()).isEqualTo(repeated.toJson());
        assertThat(first.manifestDigest()).isEqualTo(repeated.manifestDigest());
        assertThat(first.manifestDigest())
            .isEqualTo("fd21a1bbc060eccf5ce5a5b7c97979814f685f152f6c791aa161eabe71338099");
        assertThat(first.summary().sourceRowCount()).isEqualTo(rows.size());
        assertThat(first.rows()).hasSize(rows.size());
        assertThat(first.summary().classificationCounts()).isEqualTo(Map.of(
            "importable", 2L,
            "quarantined", 8L
        ));
        assertThat(first.manifestDigest()).matches("[0-9a-f]{64}");
        assertThat(first.summary().sourceSnapshotDigest()).matches("[0-9a-f]{64}");
        assertThat(first.summary().rowDigest()).matches("[0-9a-f]{64}");
        assertThat(first.summary().diagnosticsDigest()).matches("[0-9a-f]{64}");
        assertThat(Files.readAllBytes(fixturePath)).isEqualTo(before);

        CloudPlaceImportManifestRow importable = first.rows().get(0);
        assertThat(importable.classification()).isEqualTo("importable");
        assertThat(importable.source().travelItemId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(importable.source().externalReferences()).hasSize(2);
        assertThat(importable.canonical().canonicalCellId()).isEqualTo(CellIdCalculator.toWire(
            CellIdCalculator.fromCoords(35.815, 127.15)
        ));
        assertThat(importable.canonical().canonicalCellId())
            .isNotEqualTo(importable.source().legacyH3Index());
        assertThat(importable.canonical().openingHours()).hasSize(2);
        assertThat(importable.canonical().dateOverrides()).hasSize(1);
        assertThat(first.rows().get(1).canonical().visitMinutes()).isEqualTo(120);
        assertThat(first.rows().get(0).canonical().traits())
            .isNotEqualTo(first.rows().get(1).canonical().traits());

        assertThat(first.rows())
            .extracting(CloudPlaceImportManifestRow::reason)
            .contains(
                "missing_coordinates",
                "invalid_coordinates",
                "out_of_region_coordinates",
                "unsupported_provider",
                "missing_name",
                "duplicate_external_identity",
                "unreviewed_reuse_rights"
            );
        assertThat(first.rows()).allSatisfy(row -> {
            assertThat(row.fieldDispositions()).hasSize(33);
            assertThat(row.fieldDispositions())
                .extracting(CloudPlaceFieldDisposition::disposition)
                .allMatch(value -> value.equals("mapped")
                    || value.equals("preserved")
                    || value.equals("rejected"));
        });
        assertThat(importable.fieldDispositions())
            .filteredOn(field -> field.sourceField().equals("legacy_h3_index"))
            .singleElement()
            .extracting(CloudPlaceFieldDisposition::disposition)
            .isEqualTo("preserved");
        assertThat(first.rows().get(2).fieldDispositions())
            .filteredOn(field -> field.sourceField().equals("latitude"))
            .singleElement()
            .extracting(CloudPlaceFieldDisposition::disposition)
            .isEqualTo("rejected");
    }

    @Test
    void carriesDuplicateAndOrphanExternalReferenceDiagnosticsIntoTheManifest() {
        CloudPlaceReconciliationResult reconciliation = new CloudPlaceReconciler().reconcile(List.of(
            rawRow("source-1", "KAKAO", "duplicate"),
            rawRow("source-1", "KAKAO", "duplicate"),
            rawRow(null, "GOOGLE", "orphan")
        ));

        CloudPlaceImportManifest manifest = dryRun.previewImportManifestFixture(reconciliation);

        assertThat(manifest.summary().sourceRowCount()).isEqualTo(1);
        assertThat(manifest.summary().duplicateExternalReferenceCount()).isEqualTo(1);
        assertThat(manifest.summary().orphanExternalReferenceCount()).isEqualTo(1);
        assertThat(manifest.rows()).hasSize(1);
    }

    private List<CloudPlaceSourceRow> fixtureRows() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream(
            "/compat/cloud-place-fixture.json"
        )) {
            assertThat(fixture).isNotNull();
            return json.readValue(fixture, new TypeReference<>() {});
        }
    }

    private static CloudPlaceRow rawRow(
        String travelItemId,
        String provider,
        String externalId
    ) {
        return new CloudPlaceRow(
            travelItemId,
            "BUSINESS",
            "fixture",
            null,
            35.815,
            127.15,
            null,
            "KAKAO_LOCAL",
            "kakao-1",
            "ACTIVE",
            "OUTDOOR",
            null,
            "legacy-cell",
            "8a1fb46622dffff",
            11,
            provider,
            externalId
        );
    }
}
