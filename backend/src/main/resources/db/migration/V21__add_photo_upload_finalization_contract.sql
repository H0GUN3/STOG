CREATE TABLE photo_upload_sessions (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    client_upload_id UUID NOT NULL,
    original_key TEXT NOT NULL UNIQUE,
    thumb_key TEXT NOT NULL UNIQUE,
    original_content_type TEXT NOT NULL,
    original_size_bytes BIGINT NOT NULL CHECK (original_size_bytes > 0),
    original_sha256 CHAR(64) NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),
    thumb_content_type TEXT NOT NULL,
    thumb_size_bytes BIGINT NOT NULL CHECK (thumb_size_bytes > 0),
    thumb_sha256 CHAR(64) NOT NULL CHECK (thumb_sha256 ~ '^[0-9a-f]{64}$'),
    upload_fingerprint CHAR(64) NOT NULL CHECK (upload_fingerprint ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, trip_id, client_upload_id)
);

ALTER TABLE photos
    ADD COLUMN finalize_trip_id BIGINT,
    ADD COLUMN client_upload_id UUID,
    ADD COLUMN finalize_fingerprint CHAR(64),
    ADD COLUMN original_size_bytes BIGINT,
    ADD COLUMN original_sha256 CHAR(64),
    ADD COLUMN thumb_size_bytes BIGINT,
    ADD COLUMN thumb_sha256 CHAR(64),
    ADD COLUMN finalize_visibility TEXT,
    ADD COLUMN finalize_moderation_status TEXT,
    ADD CONSTRAINT photos_finalize_contract_check CHECK (
        (finalize_trip_id IS NULL
         AND client_upload_id IS NULL
         AND finalize_fingerprint IS NULL
         AND original_size_bytes IS NULL
         AND original_sha256 IS NULL
         AND thumb_size_bytes IS NULL
         AND thumb_sha256 IS NULL
         AND finalize_visibility IS NULL
         AND finalize_moderation_status IS NULL)
        OR
        (finalize_trip_id IS NOT NULL
         AND client_upload_id IS NOT NULL
         AND finalize_fingerprint ~ '^[0-9a-f]{64}$'
         AND original_size_bytes > 0
         AND original_sha256 ~ '^[0-9a-f]{64}$'
         AND thumb_size_bytes > 0
         AND thumb_sha256 ~ '^[0-9a-f]{64}$'
         AND finalize_visibility IN ('private', 'group', 'public')
         AND finalize_moderation_status IN ('pending', 'approved', 'blocked'))
    );

CREATE UNIQUE INDEX photos_finalize_identity_unique
    ON photos(user_id, finalize_trip_id, client_upload_id)
    WHERE finalize_trip_id IS NOT NULL AND client_upload_id IS NOT NULL;

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
       OR NEW.finalize_moderation_status IS DISTINCT FROM OLD.finalize_moderation_status THEN
        RAISE EXCEPTION 'photo finalize contract is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER photo_finalize_contract_immutable
BEFORE UPDATE ON photos
FOR EACH ROW
EXECUTE FUNCTION photo_finalize_contract_is_immutable();
