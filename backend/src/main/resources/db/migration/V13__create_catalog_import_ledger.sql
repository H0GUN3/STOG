CREATE TABLE catalog_import_runs (
    id BIGSERIAL PRIMARY KEY,
    manifest_digest TEXT NOT NULL UNIQUE,
    source_row_count BIGINT NOT NULL,
    importable_count BIGINT NOT NULL,
    quarantined_count BIGINT NOT NULL,
    preserved_only_count BIGINT NOT NULL,
    created_count BIGINT NOT NULL DEFAULT 0,
    updated_count BIGINT NOT NULL DEFAULT 0,
    no_op_count BIGINT NOT NULL DEFAULT 0,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT catalog_import_runs_manifest_digest_check
        CHECK (btrim(manifest_digest) <> ''),
    CONSTRAINT catalog_import_runs_counts_check
        CHECK (
            source_row_count >= 0
            AND importable_count >= 0
            AND quarantined_count >= 0
            AND preserved_only_count >= 0
            AND created_count >= 0
            AND updated_count >= 0
            AND no_op_count >= 0
            AND source_row_count = importable_count + quarantined_count + preserved_only_count
        )
);

CREATE TABLE catalog_import_provenance (
    source_travel_item_id TEXT PRIMARY KEY,
    place_id BIGINT REFERENCES places(id) ON DELETE SET NULL,
    place_source_record_id BIGINT
        REFERENCES place_source_records(id) ON DELETE SET NULL,
    manifest_digest TEXT NOT NULL
        REFERENCES catalog_import_runs(manifest_digest) ON DELETE RESTRICT,
    raw_digest TEXT,
    row_digest TEXT NOT NULL,
    classification TEXT NOT NULL,
    quarantine_reason TEXT,
    license_decision TEXT,
    reusable_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    legacy_cell_id TEXT,
    legacy_h3_index TEXT,
    legacy_h3_resolution INTEGER,
    provider_ids JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT catalog_import_provenance_row_digest_check
        CHECK (btrim(row_digest) <> ''),
    CONSTRAINT catalog_import_provenance_classification_check
        CHECK (classification IN ('importable', 'quarantined', 'preserved_only')),
    CONSTRAINT catalog_import_provenance_reason_check
        CHECK (
            (classification = 'importable' AND quarantine_reason IS NULL)
            OR (
                classification <> 'importable'
                AND quarantine_reason IS NOT NULL
                AND btrim(quarantine_reason) <> ''
            )
        ),
    CONSTRAINT catalog_import_provenance_reusable_fields_check
        CHECK (jsonb_typeof(reusable_fields) = 'array'),
    CONSTRAINT catalog_import_provenance_provider_ids_check
        CHECK (jsonb_typeof(provider_ids) = 'object')
);

CREATE INDEX catalog_import_provenance_manifest_idx
    ON catalog_import_provenance(manifest_digest);

CREATE INDEX catalog_import_provenance_place_idx
    ON catalog_import_provenance(place_id);
