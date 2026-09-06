CREATE OR REPLACE FUNCTION photo_finalize_contract_is_immutable()
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
       OR NEW.place_id IS DISTINCT FROM OLD.place_id
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
