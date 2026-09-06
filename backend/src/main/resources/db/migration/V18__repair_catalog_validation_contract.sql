DO $migration$
DECLARE
    schema_name TEXT := current_schema();
BEGIN
    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_schedule_is_valid(
            schedule JSONB,
            requires_date BOOLEAN
        )
        RETURNS BOOLEAN
        LANGUAGE plpgsql
        IMMUTABLE
        AS $body$
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
                    OR NOT __SCHEMA__.catalog_time_is_valid(schedule_entry ->> 'opens_at')
                    OR NOT __SCHEMA__.catalog_time_is_valid(schedule_entry ->> 'closes_at')
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
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_schedule_has_overnight_interval(schedule JSONB)
        RETURNS BOOLEAN
        LANGUAGE plpgsql
        IMMUTABLE
        AS $body$
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
                    OR NOT __SCHEMA__.catalog_time_is_valid(schedule_entry ->> 'opens_at')
                    OR NOT __SCHEMA__.catalog_time_is_valid(schedule_entry ->> 'closes_at')
                THEN
                    RETURN FALSE;
                END IF;
                IF (schedule_entry ->> 'opens_at') > (schedule_entry ->> 'closes_at') THEN
                    RETURN TRUE;
                END IF;
            END LOOP;

            RETURN FALSE;
        END;
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_traits_are_valid(traits_value JSONB)
        RETURNS BOOLEAN
        LANGUAGE SQL
        IMMUTABLE
        PARALLEL SAFE
        AS $body$
            SELECT CASE
                WHEN traits_value IS NULL THEN TRUE
                WHEN jsonb_typeof(traits_value) <> 'object' THEN FALSE
                ELSE EXISTS (SELECT 1 FROM jsonb_each(traits_value))
                    AND NOT EXISTS (
                        SELECT 1
                        FROM jsonb_each(traits_value) trait
                        WHERE trait.key <> ALL (
                            ARRAY[
                                'nature',
                                'culture',
                                'food',
                                'shopping',
                                'experience',
                                'relaxation'
                            ]
                        )
                        OR trait.value <> 'true'::jsonb
                    )
            END
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_assert_public_place_eligibility(target_place_id BIGINT)
        RETURNS VOID
        LANGUAGE plpgsql
        AS $body$
        DECLARE
            current_status TEXT;
        BEGIN
            SELECT catalog_status
            INTO current_status
            FROM __SCHEMA__.places
            WHERE id = target_place_id;

            IF current_status = 'public' AND NOT EXISTS (
                SELECT 1
                FROM __SCHEMA__.place_source_records source_record
                JOIN __SCHEMA__.license_snapshots license_snapshot
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
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_validate_place_public_eligibility()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $body$
        BEGIN
            IF NEW.catalog_status = 'public' AND NOT EXISTS (
                SELECT 1
                FROM __SCHEMA__.place_source_records source_record
                JOIN __SCHEMA__.license_snapshots license_snapshot
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
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_validate_source_record_public_eligibility()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $body$
        BEGIN
            PERFORM __SCHEMA__.catalog_assert_public_place_eligibility(
                CASE WHEN TG_OP = 'DELETE' THEN OLD.place_id ELSE NEW.place_id END
            );
            RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
        END;
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_validate_license_snapshot_public_eligibility()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $body$
        DECLARE
            affected_place_id BIGINT;
        BEGIN
            FOR affected_place_id IN
                SELECT DISTINCT place_id
                FROM __SCHEMA__.place_source_records
                WHERE license_snapshot_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END
            LOOP
                PERFORM __SCHEMA__.catalog_assert_public_place_eligibility(affected_place_id);
            END LOOP;
            RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
        END;
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_validate_place_source_image()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $body$
        BEGIN
            IF NEW.reusable AND NOT EXISTS (
                SELECT 1
                FROM __SCHEMA__.place_source_records source_record
                JOIN __SCHEMA__.catalog_sources catalog_source
                    ON catalog_source.id = source_record.catalog_source_id
                JOIN __SCHEMA__.license_snapshots license_snapshot
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
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));

    EXECUTE replace($definition$
        CREATE OR REPLACE FUNCTION __SCHEMA__.catalog_validate_place_alias_source_record()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $body$
        BEGIN
            IF NEW.source_record_id IS NOT NULL AND NOT EXISTS (
                SELECT 1
                FROM __SCHEMA__.place_source_records
                WHERE id = NEW.source_record_id
                  AND place_id = NEW.place_id
            ) THEN
                RAISE EXCEPTION 'place alias source record must belong to the same place';
            END IF;
            RETURN NEW;
        END;
        $body$
    $definition$, '__SCHEMA__', quote_ident(schema_name));
END
$migration$;
