package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable dry-run output. Creating or serializing this object does not have a
 * datasource, a writer, or an external-service dependency.
 */
public record CloudPlaceImportManifest(
    @JsonProperty("format_version") String formatVersion,
    @JsonProperty("rows") List<CloudPlaceImportManifestRow> rows,
    @JsonProperty("summary") CloudPlaceImportManifestSummary summary,
    @JsonProperty("manifest_digest") String manifestDigest
) {
    private static final String FORMAT_VERSION = "legacy-place-import-manifest-v1";

    public CloudPlaceImportManifest {
        rows = List.copyOf(rows);
    }

    static CloudPlaceImportManifest from(
        List<CloudPlaceNormalization> normalizations,
        long reconciledDuplicateReferences,
        long orphanReferences
    ) {
        List<CloudPlaceImportManifestRow> unsignedRows = normalizations.stream()
            .map(CloudPlaceImportManifest::unsignedRow)
            .toList();
        List<CloudPlaceImportManifestRow> rows = unsignedRows.stream()
            .map(row -> new CloudPlaceImportManifestRow(
                row.sourceTravelItemId(),
                row.classification(),
                row.reason(),
                row.source(),
                row.canonical(),
                row.fieldDispositions(),
                row.diagnostics(),
                CloudPlaceJson.sha256(row)
            ))
            .toList();

        Map<String, Long> classifications = new TreeMap<>();
        long externalReferences = 0;
        long duplicateReferencesInRows = 0;
        List<CloudPlaceSourceRow> sources = new ArrayList<>();
        List<List<String>> diagnostics = new ArrayList<>();
        for (CloudPlaceImportManifestRow row : rows) {
            classifications.merge(row.classification(), 1L, Long::sum);
            externalReferences += row.source().externalReferences().size();
            duplicateReferencesInRows += duplicateReferences(row.source());
            sources.add(row.source());
            diagnostics.add(row.diagnostics());
        }
        long duplicateReferences = Math.max(
            duplicateReferencesInRows,
            reconciledDuplicateReferences
        );
        CloudPlaceImportManifestSummary summary = new CloudPlaceImportManifestSummary(
            rows.size(),
            classifications,
            externalReferences,
            duplicateReferences,
            orphanReferences,
            CloudPlaceJson.sha256(sources),
            CloudPlaceJson.sha256(rows.stream().map(CloudPlaceImportManifestRow::rowDigest).toList()),
            CloudPlaceJson.sha256(diagnostics)
        );
        CloudPlaceImportManifest unsigned = new CloudPlaceImportManifest(
            FORMAT_VERSION,
            rows,
            summary,
            null
        );
        return new CloudPlaceImportManifest(
            FORMAT_VERSION,
            rows,
            summary,
            CloudPlaceJson.sha256(unsigned)
        );
    }

    public String toJson() {
        return CloudPlaceJson.json(this);
    }

    private static CloudPlaceImportManifestRow unsignedRow(
        CloudPlaceNormalization normalization
    ) {
        CloudPlaceCanonicalRecord canonical = "importable".equals(normalization.classification())
            ? CloudPlaceCanonicalRecord.from(normalization)
            : null;
        return new CloudPlaceImportManifestRow(
            normalization.sourceRow().travelItemId(),
            normalization.classification(),
            normalization.reason(),
            normalization.sourceRow(),
            canonical,
            fieldDispositions(normalization),
            normalization.diagnostics(),
            null
        );
    }

    private static List<CloudPlaceFieldDisposition> fieldDispositions(
        CloudPlaceNormalization normalization
    ) {
        String reason = normalization.reason();
        List<CloudPlaceFieldDisposition> fields = new ArrayList<>();
        fields.add(preserved("travel_item_id", "manifest.provenance.source_travel_item_id"));
        fields.add(mapped("item_type", "places.category"));
        fields.add(mapped("public_cell_eligible", "places.public_cell_eligible"));
        fields.add(disposition("name", "places.name", reason, "missing_name"));
        fields.add(mapped("description", "place_details.description"));
        fields.add(disposition("latitude", "places.lat", reason,
            "missing_coordinates", "invalid_coordinates", "out_of_region_coordinates"));
        fields.add(disposition("longitude", "places.lng", reason,
            "missing_coordinates", "invalid_coordinates", "out_of_region_coordinates"));
        fields.add(mapped("address", "place_details.address"));
        fields.add(disposition("source", "catalog_sources.provider_type", reason, "unsupported_source"));
        fields.add(disposition("source_item_id", "place_source_records.external_id", reason,
            "missing_external_id"));
        fields.add(mapped("status", "place_source_records.active"));
        fields.add(mapped("environment_type", "manifest.canonical.environment_type"));
        fields.add(mapped("estimated_cost", "place_details.estimated_cost"));
        fields.add(preserved("legacy_cell_id", "manifest.provenance.legacy_cell_id"));
        fields.add(preserved("legacy_h3_index", "manifest.provenance.legacy_h3_11"));
        fields.add(preserved("legacy_h3_resolution", "manifest.provenance.legacy_h3_resolution"));
        fields.add(disposition("external_references", "manifest.provenance.provider_ids", reason,
            "unsupported_provider", "missing_external_id", "duplicate_external_identity"));
        fields.add(disposition("raw_digest", "place_source_records.source_digest", reason,
            "missing_raw_digest"));
        fields.add(disposition("license_decision", "manifest.provenance.license_decision", reason,
            "unreviewed_reuse_rights", "forbidden_reuse_rights"));
        fields.add(disposition("reusable_fields", "license_snapshots.reusable_fields", reason,
            "unreviewed_reuse_rights", "forbidden_reuse_rights"));
        fields.add(disposition("opening_hours", "place_details.opening_hours", reason,
            "invalid_opening_hours"));
        fields.add(disposition("date_overrides", "place_details.date_overrides", reason,
            "invalid_date_overrides"));
        fields.add(disposition("cross_midnight", "place_details.cross_midnight", reason,
            "invalid_cross_midnight"));
        fields.add(disposition("visit_minutes", "place_details.visit_minutes", reason,
            "invalid_visit_minutes"));
        fields.add(disposition("visit_minutes_source", "place_details.visit_minutes_source", reason,
            "invalid_visit_minutes"));
        fields.add(disposition("visit_minutes_override", "place_details.visit_minutes_override", reason,
            "invalid_visit_minutes"));
        fields.add(disposition("traits", "place_details.traits", reason, "invalid_traits"));
        fields.add(disposition("traits_source", "place_details.traits_source", reason,
            "invalid_traits"));
        fields.add(disposition("traits_model", "place_details.traits_model", reason,
            "invalid_traits"));
        fields.add(disposition("traits_updated_at", "place_details.traits_updated_at", reason,
            "invalid_traits"));
        fields.add(mapped("source_updated_at", "place_details.source_updated_at"));
        fields.add(preserved("user_constraint_arrays", "manifest.preserved_user_constraints"));
        fields.add(mapped("source_images", "place_source_images"));
        return List.copyOf(fields);
    }

    private static CloudPlaceFieldDisposition mapped(String sourceField, String target) {
        return new CloudPlaceFieldDisposition(sourceField, "mapped", target, null);
    }

    private static CloudPlaceFieldDisposition preserved(String sourceField, String target) {
        return new CloudPlaceFieldDisposition(sourceField, "preserved", target, null);
    }

    private static CloudPlaceFieldDisposition disposition(
        String sourceField,
        String target,
        String manifestReason,
        String... rejectedReasons
    ) {
        for (String rejectedReason : rejectedReasons) {
            if (rejectedReason.equals(manifestReason)) {
                return new CloudPlaceFieldDisposition(
                    sourceField,
                    "rejected",
                    target,
                    rejectedReason
                );
            }
        }
        return mapped(sourceField, target);
    }

    private static long duplicateReferences(CloudPlaceSourceRow row) {
        return row.externalReferences().size()
            - row.externalReferences().stream().distinct().count();
    }
}
