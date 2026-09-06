ALTER TABLE photos
    ADD COLUMN place_id BIGINT REFERENCES places(id) ON DELETE SET NULL,
    ADD COLUMN place_name_snapshot TEXT,
    ADD COLUMN place_resolution_status TEXT,
    ADD COLUMN location_accuracy_m DOUBLE PRECISION,
    ADD COLUMN location_provenance TEXT,
    ADD COLUMN public_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN publication_status TEXT,
    ADD COLUMN finalize_place_id BIGINT,
    ADD COLUMN finalize_place_name_snapshot TEXT,
    ADD COLUMN finalize_place_resolution_status TEXT,
    ADD COLUMN finalize_publication_status TEXT,
    ADD CONSTRAINT photos_location_accuracy_nonnegative_check CHECK (
        location_accuracy_m IS NULL OR location_accuracy_m >= 0
    ),
    ADD CONSTRAINT photos_location_metadata_coordinates_check CHECK (
        (location_accuracy_m IS NULL AND location_provenance IS NULL)
        OR (lat IS NOT NULL AND lng IS NOT NULL AND location_provenance IS NOT NULL)
    ),
    ADD CONSTRAINT photos_location_provenance_check CHECK (
        location_provenance IS NULL
        OR location_provenance IN ('camera_foreground', 'gallery_exif')
    ),
    ADD CONSTRAINT photos_place_resolution_status_check CHECK (
        place_resolution_status IS NULL
        OR place_resolution_status IN ('matched', 'no_match')
    ),
    ADD CONSTRAINT photos_place_resolution_consistency_check CHECK (
        (place_resolution_status IS NULL AND place_id IS NULL AND place_name_snapshot IS NULL)
        OR (place_resolution_status = 'no_match' AND place_id IS NULL AND place_name_snapshot IS NULL)
        OR (place_resolution_status = 'matched' AND place_id IS NOT NULL
            AND place_name_snapshot IS NOT NULL AND btrim(place_name_snapshot) <> '')
    ),
    ADD CONSTRAINT photos_publication_status_check CHECK (
        publication_status IS NULL OR publication_status IN (
            'private', 'group', 'trip_not_public', 'moderation_pending', 'public'
        )
    ),
    ADD CONSTRAINT photos_finalize_place_resolution_status_check CHECK (
        finalize_place_resolution_status IS NULL
        OR finalize_place_resolution_status IN ('matched', 'no_match')
    ),
    ADD CONSTRAINT photos_finalize_place_snapshot_consistency_check CHECK (
        (finalize_place_resolution_status IS NULL AND finalize_place_id IS NULL
            AND finalize_place_name_snapshot IS NULL)
        OR (finalize_place_resolution_status = 'no_match' AND finalize_place_id IS NULL
            AND finalize_place_name_snapshot IS NULL)
        OR (finalize_place_resolution_status = 'matched' AND finalize_place_id IS NOT NULL
            AND finalize_place_name_snapshot IS NOT NULL
            AND btrim(finalize_place_name_snapshot) <> '')
    ),
    ADD CONSTRAINT photos_finalize_publication_status_check CHECK (
        finalize_publication_status IS NULL OR finalize_publication_status IN (
            'private', 'group', 'trip_not_public', 'moderation_pending', 'public'
        )
    );

CREATE INDEX photos_place_id_idx ON photos(place_id) WHERE place_id IS NOT NULL;

DROP TRIGGER photo_finalize_contract_immutable ON photos;
DROP FUNCTION photo_finalize_contract_is_immutable();

CREATE FUNCTION photo_finalize_contract_is_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.source IS DISTINCT FROM OLD.source
       OR NEW.cell_id IS DISTINCT FROM OLD.cell_id
       OR NEW.lat IS DISTINCT FROM OLD.lat
       OR NEW.lng IS DISTINCT FROM OLD.lng
       OR NEW.taken_at IS DISTINCT FROM OLD.taken_at
       OR NEW.original_key IS DISTINCT FROM OLD.original_key
       OR NEW.thumb_key IS DISTINCT FROM OLD.thumb_key
       OR NEW.caption IS DISTINCT FROM OLD.caption
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.finalize_trip_id IS DISTINCT FROM OLD.finalize_trip_id
       OR NEW.client_upload_id IS DISTINCT FROM OLD.client_upload_id
       OR NEW.finalize_fingerprint IS DISTINCT FROM OLD.finalize_fingerprint
       OR NEW.original_size_bytes IS DISTINCT FROM OLD.original_size_bytes
       OR NEW.original_sha256 IS DISTINCT FROM OLD.original_sha256
       OR NEW.thumb_size_bytes IS DISTINCT FROM OLD.thumb_size_bytes
       OR NEW.thumb_sha256 IS DISTINCT FROM OLD.thumb_sha256
       OR NEW.finalize_visibility IS DISTINCT FROM OLD.finalize_visibility
       OR NEW.finalize_moderation_status IS DISTINCT FROM OLD.finalize_moderation_status
       OR NEW.place_name_snapshot IS DISTINCT FROM OLD.place_name_snapshot
       OR NEW.place_resolution_status IS DISTINCT FROM OLD.place_resolution_status
       OR NEW.location_accuracy_m IS DISTINCT FROM OLD.location_accuracy_m
       OR NEW.location_provenance IS DISTINCT FROM OLD.location_provenance
       OR NEW.public_consent IS DISTINCT FROM OLD.public_consent
       OR NEW.finalize_place_id IS DISTINCT FROM OLD.finalize_place_id
       OR NEW.finalize_place_name_snapshot IS DISTINCT FROM OLD.finalize_place_name_snapshot
       OR NEW.finalize_place_resolution_status IS DISTINCT FROM OLD.finalize_place_resolution_status
       OR NEW.finalize_publication_status IS DISTINCT FROM OLD.finalize_publication_status THEN
        RAISE EXCEPTION 'photo finalize contract is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER photo_finalize_contract_immutable
BEFORE UPDATE ON photos
FOR EACH ROW
EXECUTE FUNCTION photo_finalize_contract_is_immutable();
