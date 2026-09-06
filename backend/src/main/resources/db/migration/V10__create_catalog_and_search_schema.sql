ALTER TABLE places
    ADD COLUMN catalog_status TEXT NOT NULL DEFAULT 'private_reference',
    ADD COLUMN normalized_name TEXT,
    ADD COLUMN compact_name TEXT;

ALTER TABLE places
    ALTER COLUMN lat DROP NOT NULL,
    ALTER COLUMN lng DROP NOT NULL;

UPDATE places
SET normalized_name = lower(
        btrim(regexp_replace(name, '[^[:alnum:]]+', ' ', 'g'))
    ),
    compact_name = replace(
        lower(btrim(regexp_replace(name, '[^[:alnum:]]+', ' ', 'g'))),
        ' ',
        ''
    );

ALTER TABLE places
    ALTER COLUMN normalized_name SET NOT NULL,
    ALTER COLUMN compact_name SET NOT NULL,
    ADD CONSTRAINT places_catalog_status_check
        CHECK (catalog_status IN ('private_reference', 'public', 'quarantined')),
    ADD CONSTRAINT places_coordinate_pair_check
        CHECK (
            (lat IS NULL AND lng IS NULL)
            OR (lat IS NOT NULL AND lng IS NOT NULL)
        ),
    ADD CONSTRAINT places_cell_coordinates_check
        CHECK (cell_id IS NULL OR (lat IS NOT NULL AND lng IS NOT NULL)),
    ADD CONSTRAINT places_normalized_name_check
        CHECK (
            normalized_name = lower(normalized_name)
            AND normalized_name = btrim(regexp_replace(normalized_name, '\s+', ' ', 'g'))
            AND normalized_name !~ '[^[:alnum:] ]'
            AND normalized_name <> ''
        ),
    ADD CONSTRAINT places_compact_name_check
        CHECK (
            compact_name = replace(normalized_name, ' ', '')
            AND compact_name <> ''
        );

CREATE INDEX places_normalized_name_trgm_idx
    ON places USING GIN (normalized_name gin_trgm_ops);

CREATE INDEX places_compact_name_trgm_idx
    ON places USING GIN (compact_name gin_trgm_ops);

CREATE TABLE catalog_sources (
    id BIGSERIAL PRIMARY KEY,
    source_key TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    provider_type TEXT NOT NULL,
    homepage_url TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT catalog_sources_source_key_check CHECK (btrim(source_key) <> '')
);

CREATE TABLE license_snapshots (
    id BIGSERIAL PRIMARY KEY,
    catalog_source_id BIGINT NOT NULL
        REFERENCES catalog_sources(id) ON DELETE RESTRICT,
    license_name TEXT NOT NULL,
    terms_url TEXT,
    reviewed_at TIMESTAMPTZ NOT NULL,
    valid_from DATE NOT NULL,
    valid_until DATE,
    allows_public_discovery BOOLEAN NOT NULL DEFAULT FALSE,
    reusable_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    digest TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT license_snapshots_source_identity_unique
        UNIQUE (id, catalog_source_id),
    CONSTRAINT license_snapshots_digest_check CHECK (btrim(digest) <> ''),
    CONSTRAINT license_snapshots_validity_check CHECK (
        valid_until IS NULL OR valid_until > valid_from
    ),
    CONSTRAINT license_snapshots_reusable_fields_check CHECK (
        jsonb_typeof(reusable_fields) = 'array'
    ),
    CONSTRAINT license_snapshots_public_discovery_fields_check CHECK (
        NOT allows_public_discovery OR jsonb_array_length(reusable_fields) > 0
    )
);

CREATE INDEX license_snapshots_source_valid_from_idx
    ON license_snapshots(catalog_source_id, valid_from DESC);

CREATE INDEX license_snapshots_source_valid_until_idx
    ON license_snapshots(catalog_source_id, valid_until);

CREATE TABLE place_source_records (
    id BIGSERIAL PRIMARY KEY,
    place_id BIGINT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    catalog_source_id BIGINT NOT NULL
        REFERENCES catalog_sources(id) ON DELETE RESTRICT,
    license_snapshot_id BIGINT NOT NULL
        REFERENCES license_snapshots(id) ON DELETE RESTRICT,
    external_id TEXT NOT NULL,
    source_digest TEXT NOT NULL UNIQUE,
    source_updated_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    imported_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT place_source_records_source_identity_unique
        UNIQUE (catalog_source_id, external_id),
    CONSTRAINT place_source_records_license_source_fk
        FOREIGN KEY (license_snapshot_id, catalog_source_id)
        REFERENCES license_snapshots(id, catalog_source_id)
        ON DELETE RESTRICT,
    CONSTRAINT place_source_records_external_id_check CHECK (btrim(external_id) <> ''),
    CONSTRAINT place_source_records_source_digest_check CHECK (btrim(source_digest) <> '')
);

CREATE INDEX place_source_records_place_active_idx
    ON place_source_records(place_id, active);

CREATE INDEX place_source_records_source_active_idx
    ON place_source_records(catalog_source_id, active);

CREATE UNIQUE INDEX place_source_records_active_place_source_unique
    ON place_source_records(place_id, catalog_source_id)
    WHERE active;

CREATE TABLE place_source_images (
    id BIGSERIAL PRIMARY KEY,
    place_source_record_id BIGINT NOT NULL
        REFERENCES place_source_records(id) ON DELETE CASCADE,
    source_image_id TEXT NOT NULL,
    source_url TEXT,
    license_snapshot_id BIGINT NOT NULL
        REFERENCES license_snapshots(id) ON DELETE RESTRICT,
    source_digest TEXT NOT NULL,
    reusable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT place_source_images_record_image_unique
        UNIQUE (place_source_record_id, source_image_id),
    CONSTRAINT place_source_images_source_image_id_check
        CHECK (btrim(source_image_id) <> ''),
    CONSTRAINT place_source_images_source_digest_check
        CHECK (btrim(source_digest) <> '')
);

CREATE INDEX place_source_images_record_idx
    ON place_source_images(place_source_record_id);

CREATE TABLE place_aliases (
    id BIGSERIAL PRIMARY KEY,
    place_id BIGINT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    alias TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    compact_name TEXT NOT NULL,
    source_record_id BIGINT
        REFERENCES place_source_records(id) ON DELETE SET NULL,
    alias_source TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT place_aliases_place_normalized_name_unique
        UNIQUE (place_id, normalized_name),
    CONSTRAINT place_aliases_normalized_name_check
        CHECK (
            normalized_name = lower(normalized_name)
            AND normalized_name = btrim(regexp_replace(normalized_name, '\s+', ' ', 'g'))
            AND normalized_name !~ '[^[:alnum:] ]'
            AND normalized_name <> ''
        ),
    CONSTRAINT place_aliases_compact_name_check
        CHECK (
            compact_name = replace(normalized_name, ' ', '')
            AND compact_name <> ''
        ),
    CONSTRAINT place_aliases_source_check CHECK (btrim(alias_source) <> '')
);

CREATE INDEX place_aliases_normalized_name_trgm_idx
    ON place_aliases USING GIN (normalized_name gin_trgm_ops);

CREATE INDEX place_aliases_compact_name_trgm_idx
    ON place_aliases USING GIN (compact_name gin_trgm_ops);

CREATE OR REPLACE FUNCTION catalog_time_is_valid(time_value TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT time_value ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
$$;

CREATE OR REPLACE FUNCTION catalog_schedule_is_valid(
    schedule JSONB,
    requires_date BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    schedule_entry JSONB;
BEGIN
    IF schedule IS NULL THEN
        RETURN TRUE;
    END IF;
    IF jsonb_typeof(schedule) <> 'array' THEN
        RETURN FALSE;
    END IF;

    FOR schedule_entry IN SELECT value FROM jsonb_array_elements(schedule)
    LOOP
        IF jsonb_typeof(schedule_entry) <> 'object'
            OR jsonb_typeof(schedule_entry -> 'opens_at') IS DISTINCT FROM 'string'
            OR jsonb_typeof(schedule_entry -> 'closes_at') IS DISTINCT FROM 'string'
            OR NOT catalog_time_is_valid(schedule_entry ->> 'opens_at')
            OR NOT catalog_time_is_valid(schedule_entry ->> 'closes_at')
        THEN
            RETURN FALSE;
        END IF;

        IF requires_date THEN
            IF jsonb_typeof(schedule_entry -> 'date') IS DISTINCT FROM 'string'
                OR btrim(schedule_entry ->> 'date') = ''
            THEN
                RETURN FALSE;
            END IF;
            BEGIN
                PERFORM (schedule_entry ->> 'date')::DATE;
            EXCEPTION WHEN OTHERS THEN
                RETURN FALSE;
            END;
        ELSIF jsonb_typeof(schedule_entry -> 'day') IS DISTINCT FROM 'string'
            OR btrim(schedule_entry ->> 'day') = ''
        THEN
            RETURN FALSE;
        END IF;
    END LOOP;

    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION catalog_schedule_has_overnight_interval(schedule JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    schedule_entry JSONB;
BEGIN
    IF schedule IS NULL OR jsonb_typeof(schedule) <> 'array' THEN
        RETURN FALSE;
    END IF;

    FOR schedule_entry IN SELECT value FROM jsonb_array_elements(schedule)
    LOOP
        IF jsonb_typeof(schedule_entry) <> 'object'
            OR jsonb_typeof(schedule_entry -> 'opens_at') IS DISTINCT FROM 'string'
            OR jsonb_typeof(schedule_entry -> 'closes_at') IS DISTINCT FROM 'string'
            OR NOT catalog_time_is_valid(schedule_entry ->> 'opens_at')
            OR NOT catalog_time_is_valid(schedule_entry ->> 'closes_at')
        THEN
            RETURN FALSE;
        END IF;
        IF (schedule_entry ->> 'opens_at') > (schedule_entry ->> 'closes_at') THEN
            RETURN TRUE;
        END IF;
    END LOOP;

    RETURN FALSE;
END;
$$;

CREATE OR REPLACE FUNCTION catalog_traits_are_valid(traits_value JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT traits_value IS NULL OR (
        jsonb_typeof(traits_value) = 'object'
        AND traits_value ?& ARRAY['nature', 'history', 'local', 'food', 'festival', 'record']
        AND traits_value - ARRAY['nature', 'history', 'local', 'food', 'festival', 'record'] = '{}'::jsonb
        AND jsonb_typeof(traits_value -> 'nature') = 'number'
        AND jsonb_typeof(traits_value -> 'history') = 'number'
        AND jsonb_typeof(traits_value -> 'local') = 'number'
        AND jsonb_typeof(traits_value -> 'food') = 'number'
        AND jsonb_typeof(traits_value -> 'festival') = 'number'
        AND jsonb_typeof(traits_value -> 'record') = 'number'
    )
$$;

CREATE TABLE place_details (
    place_id BIGINT PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,
    address TEXT,
    description TEXT,
    contact JSONB,
    accessibility JSONB,
    opening_hours JSONB,
    date_overrides JSONB,
    cross_midnight BOOLEAN NOT NULL DEFAULT FALSE,
    estimated_cost JSONB,
    visit_minutes INTEGER,
    visit_minutes_source TEXT,
    visit_minutes_override INTEGER,
    traits JSONB,
    traits_source TEXT,
    traits_model TEXT,
    traits_updated_at TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ,
    CONSTRAINT place_details_opening_hours_check
        CHECK (catalog_schedule_is_valid(opening_hours, FALSE)),
    CONSTRAINT place_details_date_overrides_check
        CHECK (catalog_schedule_is_valid(date_overrides, TRUE)),
    CONSTRAINT place_details_cross_midnight_check
        CHECK (
            cross_midnight = (
                catalog_schedule_has_overnight_interval(opening_hours)
                OR catalog_schedule_has_overnight_interval(date_overrides)
            )
        ),
    CONSTRAINT place_details_visit_minutes_check CHECK (
        (visit_minutes IS NULL AND visit_minutes_source IS NULL AND visit_minutes_override IS NULL)
        OR (
            visit_minutes > 0
            AND visit_minutes_source IS NOT NULL
            AND btrim(visit_minutes_source) <> ''
            AND (visit_minutes_override IS NULL OR visit_minutes_override > 0)
        )
    ),
    CONSTRAINT place_details_traits_check CHECK (catalog_traits_are_valid(traits)),
    CONSTRAINT place_details_traits_provenance_check CHECK (
        (traits IS NULL AND traits_source IS NULL AND traits_model IS NULL AND traits_updated_at IS NULL)
        OR (
            traits IS NOT NULL
            AND traits_source IS NOT NULL
            AND btrim(traits_source) <> ''
            AND traits_model IS NOT NULL
            AND btrim(traits_model) <> ''
            AND traits_updated_at IS NOT NULL
        )
    )
);

CREATE OR REPLACE FUNCTION catalog_assert_public_place_eligibility(target_place_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    current_status TEXT;
BEGIN
    SELECT catalog_status
    INTO current_status
    FROM places
    WHERE id = target_place_id;

    IF current_status = 'public' AND NOT EXISTS (
        SELECT 1
        FROM place_source_records source_record
        JOIN license_snapshots license_snapshot
            ON license_snapshot.id = source_record.license_snapshot_id
        WHERE source_record.place_id = target_place_id
          AND source_record.active
          AND license_snapshot.allows_public_discovery
          AND license_snapshot.valid_from <= CURRENT_DATE
          AND (
              license_snapshot.valid_until IS NULL
              OR license_snapshot.valid_until >= CURRENT_DATE
          )
          AND jsonb_array_length(license_snapshot.reusable_fields) > 0
    ) THEN
        RAISE EXCEPTION
            'public place % requires an active source record with an applicable license snapshot',
            target_place_id;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION catalog_validate_place_public_eligibility()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.catalog_status = 'public' AND NOT EXISTS (
        SELECT 1
        FROM place_source_records source_record
        JOIN license_snapshots license_snapshot
            ON license_snapshot.id = source_record.license_snapshot_id
        WHERE source_record.place_id = NEW.id
          AND source_record.active
          AND license_snapshot.allows_public_discovery
          AND license_snapshot.valid_from <= CURRENT_DATE
          AND (
              license_snapshot.valid_until IS NULL
              OR license_snapshot.valid_until >= CURRENT_DATE
          )
          AND jsonb_array_length(license_snapshot.reusable_fields) > 0
    ) THEN
        RAISE EXCEPTION
            'public place % requires an active source record with an applicable license snapshot',
            NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER places_public_catalog_eligibility_trigger
BEFORE INSERT OR UPDATE OF catalog_status ON places
FOR EACH ROW
WHEN (NEW.catalog_status = 'public')
EXECUTE FUNCTION catalog_validate_place_public_eligibility();

CREATE OR REPLACE FUNCTION catalog_validate_source_record_public_eligibility()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM catalog_assert_public_place_eligibility(
        CASE WHEN TG_OP = 'DELETE' THEN OLD.place_id ELSE NEW.place_id END
    );
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER place_source_records_public_eligibility_trigger
AFTER INSERT OR UPDATE OF place_id, license_snapshot_id, active OR DELETE
ON place_source_records
FOR EACH ROW
EXECUTE FUNCTION catalog_validate_source_record_public_eligibility();

CREATE OR REPLACE FUNCTION catalog_validate_license_snapshot_public_eligibility()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_place_id BIGINT;
BEGIN
    FOR affected_place_id IN
        SELECT DISTINCT place_id
        FROM place_source_records
        WHERE license_snapshot_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
    LOOP
        PERFORM catalog_assert_public_place_eligibility(affected_place_id);
    END LOOP;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER license_snapshots_public_eligibility_trigger
AFTER UPDATE OF allows_public_discovery, reusable_fields, valid_from, valid_until OR DELETE
ON license_snapshots
FOR EACH ROW
EXECUTE FUNCTION catalog_validate_license_snapshot_public_eligibility();

CREATE OR REPLACE FUNCTION catalog_validate_place_source_image()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reusable AND NOT EXISTS (
        SELECT 1
        FROM place_source_records source_record
        JOIN catalog_sources catalog_source
            ON catalog_source.id = source_record.catalog_source_id
        JOIN license_snapshots license_snapshot
            ON license_snapshot.id = NEW.license_snapshot_id
           AND license_snapshot.catalog_source_id = source_record.catalog_source_id
        WHERE source_record.id = NEW.place_source_record_id
          AND catalog_source.provider_type <> 'google'
          AND license_snapshot.reusable_fields ? 'image'
    ) THEN
        RAISE EXCEPTION
            'reusable source image requires a non-Google source and image reuse rights';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_source_images_reuse_trigger
BEFORE INSERT OR UPDATE OF place_source_record_id, license_snapshot_id, reusable
ON place_source_images
FOR EACH ROW
EXECUTE FUNCTION catalog_validate_place_source_image();

CREATE OR REPLACE FUNCTION catalog_validate_place_alias_source_record()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_record_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM place_source_records
        WHERE id = NEW.source_record_id
          AND place_id = NEW.place_id
    ) THEN
        RAISE EXCEPTION 'place alias source record must belong to the same place';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_aliases_source_record_trigger
BEFORE INSERT OR UPDATE OF place_id, source_record_id
ON place_aliases
FOR EACH ROW
EXECUTE FUNCTION catalog_validate_place_alias_source_record();
