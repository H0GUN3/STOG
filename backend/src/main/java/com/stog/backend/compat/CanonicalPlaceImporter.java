package com.stog.backend.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.place.search.PlaceSearchNormalizer;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a verified task-8 manifest into the local canonical datasource only.
 * It deliberately has no dependency on the legacy read datasource or any
 * external client.
 */
@Service
@Profile("local-import")
public class CanonicalPlaceImporter {
    private static final String MANIFEST_FORMAT_VERSION = "legacy-place-import-manifest-v1";
    private static final Set<String> PUBLIC_SEARCH_SOURCE_FIELDS = Set.of("name", "address");
    private static final Set<String> APPROVED_LICENSES = Set.of(
        "APPROVED",
        "APPROVED_PUBLIC_REUSE",
        "PUBLIC_REUSE_APPROVED"
    );

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final PlaceSearchNormalizer names;
    private final CloudPlaceNormalizer normalizer = new CloudPlaceNormalizer();

    public CanonicalPlaceImporter(
        JdbcClient jdbc,
        DataSource dataSource,
        PlaceSearchNormalizer names
    ) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.names = names;
    }

    /**
     * Imports one immutable manifest. The manifest digest is the run key, so an
     * identical rerun returns without changing canonical rows.
     */
    @Transactional
    public CanonicalPlaceImportResult importManifest(CloudPlaceImportManifest manifest) {
        validateManifest(manifest);

        ImportRun existingRun = findRun(manifest.manifestDigest());
        if (existingRun != null) {
            return existingRun.asNoOp();
        }

        ImportCounts manifestCounts = ImportCounts.from(manifest.summary());
        insertRun(manifest.manifestDigest(), manifestCounts);

        long created = 0;
        long updated = 0;
        long unchanged = 0;
        for (CloudPlaceImportManifestRow row : manifest.rows()) {
            switch (row.classification()) {
                case "importable" -> {
                    try {
                        ImportedRecord imported = inSourceSavepoint(
                            () -> importRecord(row, manifest.manifestDigest())
                        );
                        switch (imported.operation()) {
                            case CREATED -> created++;
                            case UPDATED -> updated++;
                            case NO_OP -> unchanged++;
                        }
                    } catch (ImportRejectedException error) {
                        quarantine(row, error.reason(), manifest.manifestDigest());
                    } catch (RuntimeException error) {
                        // Do not leak database implementation details into retained provenance.
                        quarantine(row, "canonical_write_rejected", manifest.manifestDigest());
                    }
                }
                case "quarantined" -> quarantine(row, row.reason(), manifest.manifestDigest());
                case "preserved_only" -> preserveOnly(row, manifest.manifestDigest());
                default -> throw new IllegalArgumentException("unsupported manifest classification");
            }
        }

        updateRun(manifest.manifestDigest(), created, updated, unchanged);
        return new CanonicalPlaceImportResult(
            manifest.manifestDigest(),
            manifestCounts.sourceRowCount(),
            manifestCounts.importableCount(),
            manifestCounts.quarantinedCount(),
            manifestCounts.preservedOnlyCount(),
            created,
            updated,
            unchanged,
            false
        );
    }

    /**
     * Refreshes only approved source images while preserving existing canonical
     * place details. This is the safe path for enriching an already-imported
     * local catalog from a rights-reviewed TourAPI image snapshot.
     */
    @Transactional
    public CanonicalPlaceImportResult importImages(CloudPlaceImportManifest manifest) {
        validateManifest(manifest);
        ImportCounts manifestCounts = ImportCounts.from(manifest.summary());
        ImportRun existingRun = findRun(manifest.manifestDigest());
        if (existingRun != null) {
            return existingRun.asNoOp();
        }
        insertRun(manifest.manifestDigest(), manifestCounts);
        long updated = 0;
        for (CloudPlaceImportManifestRow row : manifest.rows()) {
            if (!"importable".equals(row.classification())) {
                continue;
            }
            CloudPlaceSourceRow source = row.source();
            ImageTarget target = imageTarget(source.sourceItemId());
            if (target == null) {
                throw new ImportRejectedException("image_place_not_found");
            }
            List<String> reusableFields = new ArrayList<>(existingReusableFields(
                target.sourceRecordId()
            ));
            reusableFields.addAll(source.reusableFields());
            long licenseSnapshotId = licenseSnapshot(
                target.catalogSourceId(),
                "public_data",
                source.licenseDecision(),
                reusableFields,
                target.publicStatus()
            );
            updateSourceLicenseSnapshot(target.sourceRecordId(), licenseSnapshotId);
            replaceSourceImages(
                target.sourceRecordId(),
                licenseSnapshotId,
                source,
                target.publicEligible()
            );
            updated++;
        }
        updateRun(manifest.manifestDigest(), 0, updated, 0);
        return new CanonicalPlaceImportResult(
            manifest.manifestDigest(),
            manifestCounts.sourceRowCount(),
            manifestCounts.importableCount(),
            manifestCounts.quarantinedCount(),
            manifestCounts.preservedOnlyCount(),
            0,
            updated,
            0,
            false
        );
    }

    private ImportedRecord importRecord(
        CloudPlaceImportManifestRow row,
        String manifestDigest
    ) {
        CloudPlaceCanonicalRecord canonical = row.canonical();
        CloudPlaceSourceRow source = row.source();
        String sourceUpdatedAt = optionalTimestamp(canonical.sourceUpdatedAt());
        JsonNode preferenceTraits = canonicalPreferenceTraits(canonical.traits());
        String traitsUpdatedAt = preferenceTraits == null
            ? null
            : requiredTimestamp(canonical.traitsUpdatedAt(), "invalid_traits");
        if (canonical.estimatedCost() != null && canonical.estimatedCost() < 0) {
            throw new ImportRejectedException("invalid_estimated_cost");
        }

        long catalogSourceId = catalogSource(canonical.provider());
        boolean publicEligible = publicLicenseAllowsExposedFields(source);
        long licenseSnapshotId = licenseSnapshot(
            catalogSourceId,
            canonical.provider(),
            source.licenseDecision(),
            source.reusableFields(),
            publicEligible
        );
        String desiredStatus = publicEligible ? "public" : "private_reference";
        SourceRecord existing = sourceRecord(catalogSourceId, canonical.externalId());
        PreviousProvenance previous = provenance(source.travelItemId());

        if (existing != null
            && previous != null
            && row.rowDigest().equals(previous.rowDigest())
            && existing.sourceDigest().equals(source.rawDigest())
            && existing.licenseSnapshotId() == licenseSnapshotId
            && existing.active()
            && existing.catalogStatus().equals(desiredStatus)) {
            upsertProvenance(
                row,
                "importable",
                null,
                existing.placeId(),
                existing.id(),
                manifestDigest
            );
            return new ImportedRecord(existing.placeId(), existing.id(), ImportOperation.NO_OP);
        }

        retirePreviousSourceRecord(previous, existing == null ? null : existing.id());

        long placeId;
        long sourceRecordId;
        ImportOperation operation;
        if (existing == null) {
            placeId = insertPlace(canonical);
            upsertDetails(
                placeId,
                canonical,
                preferenceTraits,
                sourceUpdatedAt,
                traitsUpdatedAt
            );
            sourceRecordId = insertSourceRecord(
                placeId,
                catalogSourceId,
                licenseSnapshotId,
                canonical.externalId(),
                source.rawDigest(),
                sourceUpdatedAt
            );
            operation = ImportOperation.CREATED;
        } else {
            placeId = existing.placeId();
            jdbc.sql("UPDATE places SET catalog_status = 'private_reference' WHERE id = :placeId")
                .param("placeId", placeId)
                .update();
            updatePlace(placeId, canonical);
            upsertDetails(
                placeId,
                canonical,
                preferenceTraits,
                sourceUpdatedAt,
                traitsUpdatedAt
            );
            updateSourceRecord(
                existing.id(),
                licenseSnapshotId,
                source.rawDigest(),
                sourceUpdatedAt
            );
            sourceRecordId = existing.id();
            operation = ImportOperation.UPDATED;
        }

        replaceSourceImages(sourceRecordId, licenseSnapshotId, source, publicEligible);
        upsertAlias(placeId, sourceRecordId, canonical.name());
        jdbc.sql("UPDATE places SET catalog_status = :catalogStatus WHERE id = :placeId")
            .param("catalogStatus", desiredStatus)
            .param("placeId", placeId)
            .update();
        upsertProvenance(
            row,
            "importable",
            null,
            placeId,
            sourceRecordId,
            manifestDigest
        );
        return new ImportedRecord(placeId, sourceRecordId, operation);
    }

    private void quarantine(
        CloudPlaceImportManifestRow row,
        String reason,
        String manifestDigest
    ) {
        String quarantineReason = requiredText(reason, "quarantine reason is required");
        PreviousProvenance previous = provenance(row.sourceTravelItemId());
        deactivatePreviousRecord(previous, "quarantined");
        upsertProvenance(
            row,
            "quarantined",
            quarantineReason,
            previous == null ? null : previous.placeId(),
            previous == null ? null : previous.placeSourceRecordId(),
            manifestDigest
        );
    }

    private void preserveOnly(CloudPlaceImportManifestRow row, String manifestDigest) {
        PreviousProvenance previous = provenance(row.sourceTravelItemId());
        deactivatePreviousRecord(previous, "private_reference");
        upsertProvenance(
            row,
            "preserved_only",
            requiredText(row.reason(), "preserved-only reason is required"),
            previous == null ? null : previous.placeId(),
            previous == null ? null : previous.placeSourceRecordId(),
            manifestDigest
        );
    }

    private void deactivatePreviousRecord(PreviousProvenance previous, String catalogStatus) {
        if (previous == null || previous.placeId() == null) {
            return;
        }
        jdbc.sql("UPDATE places SET catalog_status = :catalogStatus WHERE id = :placeId")
            .param("catalogStatus", catalogStatus)
            .param("placeId", previous.placeId())
            .update();
        if (previous.placeSourceRecordId() != null) {
            jdbc.sql("UPDATE place_source_records SET active = FALSE WHERE id = :sourceRecordId")
                .param("sourceRecordId", previous.placeSourceRecordId())
                .update();
        }
    }

    private void retirePreviousSourceRecord(
        PreviousProvenance previous,
        Long retainedSourceRecordId
    ) {
        if (previous == null
            || previous.placeSourceRecordId() == null
            || Objects.equals(previous.placeSourceRecordId(), retainedSourceRecordId)) {
            return;
        }
        deactivatePreviousRecord(previous, "private_reference");
    }

    private long catalogSource(String provider) {
        String sourceKey = "legacy-manifest-" + provider;
        Long existing = jdbc.sql("SELECT id FROM catalog_sources WHERE source_key = :sourceKey")
            .param("sourceKey", sourceKey)
            .query(Long.class)
            .optional()
            .orElse(null);
        if (existing != null) {
            return existing;
        }
        return jdbc.sql(
                """
                INSERT INTO catalog_sources (source_key, name, provider_type, active)
                VALUES (:sourceKey, :name, :providerType, TRUE)
                RETURNING id
                """
            )
            .param("sourceKey", sourceKey)
            .param("name", "Legacy manifest " + provider)
            .param("providerType", provider)
            .query(Long.class)
            .single();
    }

    private long licenseSnapshot(
        long catalogSourceId,
        String provider,
        String decision,
        List<String> reusableFields,
        boolean allowsPublicDiscovery
    ) {
        String digest = CloudPlaceJson.sha256(Map.of(
            "provider", provider,
            "license_decision", decision,
            "reusable_fields", reusableFields
        ));
        Long existing = jdbc.sql("SELECT id FROM license_snapshots WHERE digest = :digest")
            .param("digest", digest)
            .query(Long.class)
            .optional()
            .orElse(null);
        if (existing != null) {
            return existing;
        }
        return jdbc.sql(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :catalogSourceId, :licenseName, CURRENT_TIMESTAMP, CURRENT_DATE,
                    :allowsPublicDiscovery, CAST(:reusableFields AS jsonb), :digest
                )
                RETURNING id
                """
            )
            .param("catalogSourceId", catalogSourceId)
            .param("licenseName", "Manifest " + decision)
            .param("allowsPublicDiscovery", allowsPublicDiscovery)
            .param("reusableFields", CloudPlaceJson.json(reusableFields))
            .param("digest", digest)
            .query(Long.class)
            .single();
    }

    private SourceRecord sourceRecord(long catalogSourceId, String externalId) {
        return jdbc.sql(
                """
                SELECT record.id, record.place_id, record.source_digest,
                       record.license_snapshot_id, record.active, place.catalog_status
                FROM place_source_records record
                JOIN places place ON place.id = record.place_id
                WHERE record.catalog_source_id = :catalogSourceId
                  AND record.external_id = :externalId
                """
            )
            .param("catalogSourceId", catalogSourceId)
            .param("externalId", externalId)
            .query((row, rowNumber) -> new SourceRecord(
                row.getLong("id"),
                row.getLong("place_id"),
                row.getString("source_digest"),
                row.getLong("license_snapshot_id"),
                row.getBoolean("active"),
                row.getString("catalog_status")
            ))
            .optional()
            .orElse(null);
    }

    private ImageTarget imageTarget(String externalId) {
        if (isBlank(externalId)) {
            return null;
        }
        return jdbc.sql(
                """
                SELECT record.id AS source_record_id,
                       source.id AS catalog_source_id,
                       place.catalog_status,
                       place.public_cell_eligible
                FROM place_source_records record
                JOIN catalog_sources source
                  ON source.id = record.catalog_source_id
                JOIN places place
                  ON place.id = record.place_id
                WHERE place.source = 'public_data'
                  AND place.external_id = :externalId
                  AND record.active
                ORDER BY record.id
                LIMIT 1
                """
            )
            .param("externalId", externalId)
            .query((row, rowNumber) -> new ImageTarget(
                row.getLong("source_record_id"),
                row.getLong("catalog_source_id"),
                "public".equals(row.getString("catalog_status")),
                "public".equals(row.getString("catalog_status"))
                    && row.getBoolean("public_cell_eligible")
            ))
            .optional()
            .orElse(null);
    }

    private PreviousProvenance provenance(String sourceTravelItemId) {
        return jdbc.sql(
                """
                SELECT place_id, place_source_record_id, row_digest
                FROM catalog_import_provenance
                WHERE source_travel_item_id = :sourceTravelItemId
                """
            )
            .param("sourceTravelItemId", sourceTravelItemId)
            .query((row, rowNumber) -> new PreviousProvenance(
                row.getObject("place_id", Long.class),
                row.getObject("place_source_record_id", Long.class),
                row.getString("row_digest")
            ))
            .optional()
            .orElse(null);
    }

    private long insertPlace(CloudPlaceCanonicalRecord canonical) {
        PlaceSearchNormalizer.Normalized normalized = names.normalize(canonical.name());
        if (normalized.normalized_name().isBlank()) {
            throw new ImportRejectedException("missing_name");
        }
        return jdbc.sql(
                """
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, external_id,
                    normalized_name, compact_name, public_cell_eligible
                )
                VALUES (
                    :name, :category, :latitude, :longitude, :cellId, :source, :externalId,
                    :normalizedName, :compactName, :publicCellEligible
                )
                RETURNING id
                """
            )
            .param("name", canonical.name())
            .param("category", canonical.category().toLowerCase(java.util.Locale.ROOT))
            .param("latitude", canonical.latitude())
            .param("longitude", canonical.longitude())
                    .param("cellId", canonicalCellId(canonical))
            .param("source", canonical.provider())
            .param("externalId", canonical.externalId())
            .param("normalizedName", normalized.normalized_name())
            .param("compactName", normalized.compact_name())
            .param("publicCellEligible", Boolean.TRUE.equals(canonical.publicCellEligible()))
            .query(Long.class)
            .single();
    }

    private void updatePlace(long placeId, CloudPlaceCanonicalRecord canonical) {
        PlaceSearchNormalizer.Normalized normalized = names.normalize(canonical.name());
        if (normalized.normalized_name().isBlank()) {
            throw new ImportRejectedException("missing_name");
        }
        jdbc.sql(
                """
                UPDATE places
                SET name = :name,
                    category = :category,
                    lat = :latitude,
                    lng = :longitude,
                    cell_id = :cellId,
                    source = :source,
                    external_id = :externalId,
                    normalized_name = :normalizedName,
                    compact_name = :compactName,
                    public_cell_eligible = :publicCellEligible
                WHERE id = :placeId
                """
            )
            .param("placeId", placeId)
            .param("name", canonical.name())
            .param("category", canonical.category().toLowerCase(java.util.Locale.ROOT))
            .param("latitude", canonical.latitude())
            .param("longitude", canonical.longitude())
            .param("cellId", canonicalCellId(canonical))
            .param("source", canonical.provider())
            .param("externalId", canonical.externalId())
            .param("normalizedName", normalized.normalized_name())
            .param("compactName", normalized.compact_name())
            .param("publicCellEligible", Boolean.TRUE.equals(canonical.publicCellEligible()))
            .update();
    }

    private void upsertDetails(
        long placeId,
        CloudPlaceCanonicalRecord canonical,
        JsonNode preferenceTraits,
        String sourceUpdatedAt,
        String traitsUpdatedAt
    ) {
        jdbc.sql(
                """
                INSERT INTO place_details (
                    place_id, address, description, opening_hours, date_overrides,
                    cross_midnight, estimated_cost, visit_minutes, visit_minutes_source,
                    visit_minutes_override, traits, traits_source, traits_model,
                    traits_updated_at, source_updated_at
                )
                VALUES (
                    :placeId, :address, :description, CAST(:openingHours AS jsonb),
                    CAST(:dateOverrides AS jsonb), :crossMidnight,
                    CAST(:estimatedCost AS jsonb), :visitMinutes, :visitMinutesSource,
                    :visitMinutesOverride, CAST(:traits AS jsonb), :traitsSource, :traitsModel,
                    CAST(:traitsUpdatedAt AS timestamptz), CAST(:sourceUpdatedAt AS timestamptz)
                )
                ON CONFLICT (place_id) DO UPDATE
                SET address = EXCLUDED.address,
                    description = EXCLUDED.description,
                    opening_hours = EXCLUDED.opening_hours,
                    date_overrides = EXCLUDED.date_overrides,
                    cross_midnight = EXCLUDED.cross_midnight,
                    estimated_cost = EXCLUDED.estimated_cost,
                    visit_minutes = EXCLUDED.visit_minutes,
                    visit_minutes_source = EXCLUDED.visit_minutes_source,
                    visit_minutes_override = EXCLUDED.visit_minutes_override,
                    traits = EXCLUDED.traits,
                    traits_source = EXCLUDED.traits_source,
                    traits_model = EXCLUDED.traits_model,
                    traits_updated_at = EXCLUDED.traits_updated_at,
                    source_updated_at = EXCLUDED.source_updated_at
                """
            )
            .param("placeId", placeId)
            .param("address", canonical.address())
            .param("description", canonical.description())
            .param("openingHours", json(canonical.openingHours()))
            .param("dateOverrides", json(canonical.dateOverrides()))
            .param("crossMidnight", crossMidnight(canonical))
            .param("estimatedCost", estimatedCost(canonical.estimatedCost()))
            .param("visitMinutes", canonical.visitMinutes())
            .param("visitMinutesSource", canonical.visitMinutesSource())
            .param("visitMinutesOverride", canonical.visitMinutesOverride())
            .param("traits", json(preferenceTraits))
            .param("traitsSource", preferenceTraits == null ? null : canonical.traitsSource())
            .param("traitsModel", preferenceTraits == null ? null : canonical.traitsModel())
            .param("traitsUpdatedAt", traitsUpdatedAt)
            .param("sourceUpdatedAt", sourceUpdatedAt)
            .update();
    }

    private JsonNode canonicalPreferenceTraits(JsonNode traits) {
        if (traits == null || traits.isNull() || !traits.isObject() || traits.isEmpty()) {
            return null;
        }
        Set<String> allowed = Set.of(
            "nature",
            "culture",
            "food",
            "shopping",
            "experience",
            "relaxation"
        );
        var fields = traits.fields();
        while (fields.hasNext()) {
            var trait = fields.next();
            if (!allowed.contains(trait.getKey())
                || !trait.getValue().isBoolean()
                || !trait.getValue().booleanValue()) {
                return null;
            }
        }
        return traits;
    }

    private long insertSourceRecord(
        long placeId,
        long catalogSourceId,
        long licenseSnapshotId,
        String externalId,
        String rawDigest,
        String sourceUpdatedAt
    ) {
        return jdbc.sql(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (
                    :placeId, :catalogSourceId, :licenseSnapshotId, :externalId,
                    :sourceDigest, CAST(:sourceUpdatedAt AS timestamptz), TRUE, CURRENT_TIMESTAMP
                )
                RETURNING id
                """
            )
            .param("placeId", placeId)
            .param("catalogSourceId", catalogSourceId)
            .param("licenseSnapshotId", licenseSnapshotId)
            .param("externalId", externalId)
            .param("sourceDigest", rawDigest)
            .param("sourceUpdatedAt", sourceUpdatedAt)
            .query(Long.class)
            .single();
    }

    private void updateSourceRecord(
        long sourceRecordId,
        long licenseSnapshotId,
        String rawDigest,
        String sourceUpdatedAt
    ) {
        jdbc.sql(
                """
                UPDATE place_source_records
                SET license_snapshot_id = :licenseSnapshotId,
                    source_digest = :sourceDigest,
                    source_updated_at = CAST(:sourceUpdatedAt AS timestamptz),
                    active = TRUE,
                    imported_at = CURRENT_TIMESTAMP
                WHERE id = :sourceRecordId
                """
            )
            .param("sourceRecordId", sourceRecordId)
            .param("licenseSnapshotId", licenseSnapshotId)
            .param("sourceDigest", rawDigest)
            .param("sourceUpdatedAt", sourceUpdatedAt)
            .update();
    }

    private void updateSourceLicenseSnapshot(long sourceRecordId, long licenseSnapshotId) {
        jdbc.sql(
                """
                UPDATE place_source_records
                SET license_snapshot_id = :licenseSnapshotId
                WHERE id = :sourceRecordId
                """
            )
            .param("sourceRecordId", sourceRecordId)
            .param("licenseSnapshotId", licenseSnapshotId)
            .update();
    }

    private List<String> existingReusableFields(long sourceRecordId) {
        return jdbc.sql(
                """
                SELECT jsonb_array_elements_text(snapshot.reusable_fields)
                FROM place_source_records record
                JOIN license_snapshots snapshot
                  ON snapshot.id = record.license_snapshot_id
                WHERE record.id = :sourceRecordId
                """
            )
            .param("sourceRecordId", sourceRecordId)
            .query(String.class)
            .list();
    }

    private void replaceSourceImages(
        long sourceRecordId,
        long licenseSnapshotId,
        CloudPlaceSourceRow source,
        boolean publicEligible
    ) {
        jdbc.sql("DELETE FROM place_source_images WHERE place_source_record_id = :sourceRecordId")
            .param("sourceRecordId", sourceRecordId)
            .update();
        if (!publicEligible
            || !isApprovedLicense(source.licenseDecision())
            || !hasReusableField(source, "image")) {
            return;
        }
        for (CloudPlaceSourceImage image : source.sourceImages()) {
            if (!Boolean.TRUE.equals(image.reusable())
                || isBlank(image.sourceImageId())
                || isBlank(image.sourceUrl())
                || isBlank(image.sourceDigest())
                || !isHttpsUrl(image.sourceUrl())) {
                continue;
            }
            jdbc.sql(
                    """
                    INSERT INTO place_source_images (
                        place_source_record_id, source_image_id, source_url,
                        license_snapshot_id, source_digest, reusable
                    )
                    VALUES (
                        :sourceRecordId, :sourceImageId, :sourceUrl,
                        :licenseSnapshotId, :sourceDigest, TRUE
                    )
                    ON CONFLICT (place_source_record_id, source_image_id) DO UPDATE
                    SET source_url = EXCLUDED.source_url,
                        license_snapshot_id = EXCLUDED.license_snapshot_id,
                        source_digest = EXCLUDED.source_digest,
                        reusable = EXCLUDED.reusable
                    """
                )
                .param("sourceRecordId", sourceRecordId)
                .param("sourceImageId", image.sourceImageId())
                .param("sourceUrl", image.sourceUrl())
                .param("licenseSnapshotId", licenseSnapshotId)
                .param("sourceDigest", image.sourceDigest())
                .update();
        }
    }

    private boolean hasReusableField(CloudPlaceSourceRow source, String fieldName) {
        return source.reusableFields().stream()
            .filter(Objects::nonNull)
            .map(field -> field.trim().toLowerCase(java.util.Locale.ROOT))
            .anyMatch(fieldName::equals);
    }

    private Long canonicalCellId(CloudPlaceCanonicalRecord canonical) {
        if (canonical.canonicalCellId() == null) {
            return null;
        }
        return CellIdCalculator.fromCoords(canonical.latitude(), canonical.longitude());
    }

    private boolean isApprovedLicense(String decision) {
        return decision != null
            && APPROVED_LICENSES.contains(decision.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean isHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void upsertAlias(long placeId, long sourceRecordId, String alias) {
        PlaceSearchNormalizer.Normalized normalized = names.normalize(alias);
        jdbc.sql(
                """
                INSERT INTO place_aliases (
                    place_id, alias, normalized_name, compact_name, source_record_id, alias_source
                )
                VALUES (
                    :placeId, :alias, :normalizedName, :compactName, :sourceRecordId,
                    'legacy_manifest'
                )
                ON CONFLICT (place_id, normalized_name) DO UPDATE
                SET alias = EXCLUDED.alias,
                    compact_name = EXCLUDED.compact_name,
                    source_record_id = EXCLUDED.source_record_id,
                    alias_source = EXCLUDED.alias_source
                """
            )
            .param("placeId", placeId)
            .param("alias", alias)
            .param("normalizedName", normalized.normalized_name())
            .param("compactName", normalized.compact_name())
            .param("sourceRecordId", sourceRecordId)
            .update();
    }

    private void upsertProvenance(
        CloudPlaceImportManifestRow row,
        String classification,
        String quarantineReason,
        Long placeId,
        Long placeSourceRecordId,
        String manifestDigest
    ) {
        CloudPlaceSourceRow source = row.source();
        jdbc.sql(
                """
                INSERT INTO catalog_import_provenance (
                    source_travel_item_id, place_id, place_source_record_id, manifest_digest,
                    raw_digest, row_digest, classification, quarantine_reason, license_decision,
                    reusable_fields, legacy_cell_id, legacy_h3_index, legacy_h3_resolution,
                    provider_ids, source_updated_at
                )
                VALUES (
                    :sourceTravelItemId, :placeId, :placeSourceRecordId, :manifestDigest,
                    :rawDigest, :rowDigest, :classification, :quarantineReason, :licenseDecision,
                    CAST(:reusableFields AS jsonb), :legacyCellId, :legacyH3Index,
                    :legacyH3Resolution, CAST(:providerIds AS jsonb),
                    CAST(:sourceUpdatedAt AS timestamptz)
                )
                ON CONFLICT (source_travel_item_id) DO UPDATE
                SET place_id = EXCLUDED.place_id,
                    place_source_record_id = EXCLUDED.place_source_record_id,
                    manifest_digest = EXCLUDED.manifest_digest,
                    raw_digest = EXCLUDED.raw_digest,
                    row_digest = EXCLUDED.row_digest,
                    classification = EXCLUDED.classification,
                    quarantine_reason = EXCLUDED.quarantine_reason,
                    license_decision = EXCLUDED.license_decision,
                    reusable_fields = EXCLUDED.reusable_fields,
                    legacy_cell_id = EXCLUDED.legacy_cell_id,
                    legacy_h3_index = EXCLUDED.legacy_h3_index,
                    legacy_h3_resolution = EXCLUDED.legacy_h3_resolution,
                    provider_ids = EXCLUDED.provider_ids,
                    source_updated_at = EXCLUDED.source_updated_at,
                    recorded_at = CURRENT_TIMESTAMP
                """
            )
            .param("sourceTravelItemId", source.travelItemId())
            .param("placeId", placeId)
            .param("placeSourceRecordId", placeSourceRecordId)
            .param("manifestDigest", manifestDigest)
            .param("rawDigest", source.rawDigest())
            .param("rowDigest", row.rowDigest())
            .param("classification", classification)
            .param("quarantineReason", quarantineReason)
            .param("licenseDecision", source.licenseDecision())
            .param("reusableFields", CloudPlaceJson.json(source.reusableFields()))
            .param("legacyCellId", source.legacyCellId())
            .param("legacyH3Index", source.legacyH3Index())
            .param("legacyH3Resolution", source.legacyH3Resolution())
            .param("providerIds", providerIds(source))
            .param("sourceUpdatedAt", optionalTimestamp(source.sourceUpdatedAt()))
            .update();
    }


    private void insertRun(String manifestDigest, ImportCounts counts) {
        jdbc.sql(
                """
                INSERT INTO catalog_import_runs (
                    manifest_digest, source_row_count, importable_count,
                    quarantined_count, preserved_only_count
                )
                VALUES (
                    :manifestDigest, :sourceRowCount, :importableCount,
                    :quarantinedCount, :preservedOnlyCount
                )
                """
            )
            .param("manifestDigest", manifestDigest)
            .param("sourceRowCount", counts.sourceRowCount())
            .param("importableCount", counts.importableCount())
            .param("quarantinedCount", counts.quarantinedCount())
            .param("preservedOnlyCount", counts.preservedOnlyCount())
            .update();
    }

    private void updateRun(String manifestDigest, long created, long updated, long noOp) {
        jdbc.sql(
                """
                UPDATE catalog_import_runs
                SET created_count = :createdCount,
                    updated_count = :updatedCount,
                    no_op_count = :noOpCount
                WHERE manifest_digest = :manifestDigest
                """
            )
            .param("manifestDigest", manifestDigest)
            .param("createdCount", created)
            .param("updatedCount", updated)
            .param("noOpCount", noOp)
            .update();
    }

    private ImportRun findRun(String manifestDigest) {
        return jdbc.sql(
                """
                SELECT manifest_digest, source_row_count, importable_count, quarantined_count,
                       preserved_only_count
                FROM catalog_import_runs
                WHERE manifest_digest = :manifestDigest
                """
            )
            .param("manifestDigest", manifestDigest)
            .query((row, rowNumber) -> new ImportRun(
                row.getString("manifest_digest"),
                row.getLong("source_row_count"),
                row.getLong("importable_count"),
                row.getLong("quarantined_count"),
                row.getLong("preserved_only_count")
            ))
            .optional()
            .orElse(null);
    }

    private void validateManifest(CloudPlaceImportManifest manifest) {
        Objects.requireNonNull(manifest, "manifest is required");
        if (!MANIFEST_FORMAT_VERSION.equals(manifest.formatVersion())) {
            throw new IllegalArgumentException("manifest format is unsupported");
        }
        Objects.requireNonNull(manifest.rows(), "manifest rows are required");
        Objects.requireNonNull(manifest.summary(), "manifest summary is required");
        if (manifest.rows().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("manifest rows must not contain nulls");
        }

        Map<String, CloudPlaceNormalization> normalizationsBySourceId = new HashMap<>();
        List<CloudPlaceSourceRow> sourceRows = manifest.rows().stream()
            .map(CloudPlaceImportManifestRow::source)
            .toList();
        for (CloudPlaceNormalization normalization : normalizer.normalizeAll(sourceRows)) {
            String sourceTravelItemId = requiredText(
                normalization.sourceRow().travelItemId(),
                "source travel item identity is required"
            );
            if (normalizationsBySourceId.put(sourceTravelItemId, normalization) != null) {
                throw new IllegalArgumentException("manifest source identities must be unique");
            }
        }

        Map<String, Long> classifications = new TreeMap<>();
        long externalReferences = 0;
        long duplicateReferences = 0;
        List<String> rowDigests = new ArrayList<>();
        List<List<String>> diagnostics = new ArrayList<>();
        for (CloudPlaceImportManifestRow row : manifest.rows()) {
            if (row.source() == null) {
                throw new IllegalArgumentException("manifest row source is required");
            }
            String sourceTravelItemId = requiredText(
                row.sourceTravelItemId(),
                "source travel item identity is required"
            );
            if (!sourceTravelItemId.equals(row.source().travelItemId())) {
                throw new IllegalArgumentException("manifest source identity does not match row identity");
            }
            CloudPlaceNormalization normalization = normalizationsBySourceId.get(sourceTravelItemId);
            if (normalization == null
                || !normalization.classification().equals(row.classification())
                || !Objects.equals(normalization.reason(), row.reason())) {
                throw new IllegalArgumentException("manifest row classification is not reproducible");
            }
            CloudPlaceCanonicalRecord expectedCanonical = "importable".equals(row.classification())
                ? CloudPlaceCanonicalRecord.from(normalization)
                : null;
            if (!Objects.equals(expectedCanonical, row.canonical())) {
                throw new IllegalArgumentException("manifest canonical record is not reproducible");
            }
            String expectedRowDigest = CloudPlaceJson.sha256(new CloudPlaceImportManifestRow(
                row.sourceTravelItemId(),
                row.classification(),
                row.reason(),
                row.source(),
                row.canonical(),
                row.fieldDispositions(),
                row.diagnostics(),
                null
            ));
            if (!expectedRowDigest.equals(row.rowDigest())) {
                throw new IllegalArgumentException("manifest row digest is invalid");
            }
            classifications.merge(row.classification(), 1L, Long::sum);
            externalReferences += row.source().externalReferences().size();
            duplicateReferences += row.source().externalReferences().size()
                - row.source().externalReferences().stream().distinct().count();
            rowDigests.add(row.rowDigest());
            diagnostics.add(row.diagnostics());
        }

        CloudPlaceImportManifestSummary summary = manifest.summary();
        if (summary.sourceRowCount() != manifest.rows().size()
            || !summary.classificationCounts().equals(classifications)
            || summary.externalReferenceCount() != externalReferences
            || summary.duplicateExternalReferenceCount() < duplicateReferences
            || summary.orphanExternalReferenceCount() < 0
            || !summary.sourceSnapshotDigest().equals(CloudPlaceJson.sha256(sourceRows))
            || !summary.rowDigest().equals(CloudPlaceJson.sha256(rowDigests))
            || !summary.diagnosticsDigest().equals(CloudPlaceJson.sha256(diagnostics))) {
            throw new IllegalArgumentException("manifest summary is not reconciled");
        }
        String expectedManifestDigest = CloudPlaceJson.sha256(new CloudPlaceImportManifest(
            manifest.formatVersion(),
            manifest.rows(),
            manifest.summary(),
            null
        ));
        if (!expectedManifestDigest.equals(manifest.manifestDigest())) {
            throw new IllegalArgumentException("manifest digest is invalid");
        }
    }

    private boolean publicLicenseAllowsExposedFields(CloudPlaceSourceRow source) {
        if (!isApprovedLicense(source.licenseDecision())) {
            return false;
        }
        Set<String> allowed = new HashSet<>();
        for (String field : source.reusableFields()) {
            if (field != null) {
                allowed.add(field.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        Set<String> required = new HashSet<>(PUBLIC_SEARCH_SOURCE_FIELDS);
        if (source.address() == null || source.address().isBlank()) {
            required.remove("address");
        }
        return allowed.containsAll(required);
    }

    private <T> T inSourceSavepoint(Supplier<T> work) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint();
        } catch (SQLException error) {
            throw new IllegalStateException("could not start source-record savepoint", error);
        }
        try {
            T result = work.get();
            connection.releaseSavepoint(savepoint);
            return result;
        } catch (RuntimeException error) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            throw error;
        } catch (SQLException error) {
            throw new IllegalStateException("could not release source-record savepoint", error);
        }
    }

    private String providerIds(CloudPlaceSourceRow source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ObjectNode primary = result.putObject("primary");
        put(primary, "source", source.source());
        put(primary, "source_item_id", source.sourceItemId());
        ArrayNode externalReferences = result.putArray("external_references");
        for (CloudPlaceExternalRef reference : source.externalReferences()) {
            ObjectNode external = externalReferences.addObject();
            put(external, "provider", reference.provider());
            put(external, "external_id", reference.externalId());
        }
        return CloudPlaceJson.json(result);
    }

    private static void put(ObjectNode object, String key, String value) {
        if (value == null) {
            object.putNull(key);
        } else {
            object.put(key, value);
        }
    }

    private static boolean crossMidnight(CloudPlaceCanonicalRecord canonical) {
        if (canonical.crossMidnight() != null) {
            return canonical.crossMidnight();
        }
        return hasOvernightInterval(canonical.openingHours())
            || hasOvernightInterval(canonical.dateOverrides());
    }

    private static boolean hasOvernightInterval(JsonNode schedule) {
        if (schedule == null || schedule.isNull() || !schedule.isArray()) {
            return false;
        }
        for (JsonNode entry : schedule) {
            JsonNode opensAt = entry.get("opens_at");
            JsonNode closesAt = entry.get("closes_at");
            if (opensAt != null
                && closesAt != null
                && opensAt.isTextual()
                && closesAt.isTextual()
                && opensAt.textValue().compareTo(closesAt.textValue()) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String json(JsonNode value) {
        return value == null || value.isNull() ? null : CloudPlaceJson.json(value);
    }

    private static String estimatedCost(Integer value) {
        return value == null ? null : "{\"currency\":\"KRW\",\"amount\":" + value + "}";
    }

    private static String requiredTimestamp(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new ImportRejectedException(reason);
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException firstError) {
            try {
                return OffsetDateTime.parse(value).toInstant().toString();
            } catch (DateTimeParseException ignored) {
                throw new ImportRejectedException(reason);
            }
        }
    }

    private static String optionalTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return requiredTimestamp(value, "invalid_source_updated_at");
        } catch (ImportRejectedException error) {
            return null;
        }
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private record SourceRecord(
        long id,
        long placeId,
        String sourceDigest,
        long licenseSnapshotId,
        boolean active,
        String catalogStatus
    ) {
    }

    private record ImageTarget(
        long sourceRecordId,
        long catalogSourceId,
        boolean publicStatus,
        boolean publicEligible
    ) {
    }

    private record PreviousProvenance(
        Long placeId,
        Long placeSourceRecordId,
        String rowDigest
    ) {
    }

    private record ImportedRecord(long placeId, long sourceRecordId, ImportOperation operation) {
    }

    private record ImportRun(
        String manifestDigest,
        long sourceRowCount,
        long importableCount,
        long quarantinedCount,
        long preservedOnlyCount
    ) {
        CanonicalPlaceImportResult asNoOp() {
            return new CanonicalPlaceImportResult(
                manifestDigest,
                sourceRowCount,
                importableCount,
                quarantinedCount,
                preservedOnlyCount,
                0,
                0,
                sourceRowCount,
                true
            );
        }
    }

    private record ImportCounts(
        long sourceRowCount,
        long importableCount,
        long quarantinedCount,
        long preservedOnlyCount
    ) {
        static ImportCounts from(CloudPlaceImportManifestSummary summary) {
            long importable = summary.classificationCounts().getOrDefault("importable", 0L);
            long quarantined = summary.classificationCounts().getOrDefault("quarantined", 0L);
            long preservedOnly = summary.classificationCounts().getOrDefault("preserved_only", 0L);
            if (summary.sourceRowCount() != importable + quarantined + preservedOnly) {
                throw new IllegalArgumentException("manifest classification counts do not reconcile");
            }
            return new ImportCounts(summary.sourceRowCount(), importable, quarantined, preservedOnly);
        }
    }

    private enum ImportOperation {
        CREATED,
        UPDATED,
        NO_OP
    }

    static final class ImportRejectedException extends RuntimeException {
        private final String reason;

        private ImportRejectedException(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
