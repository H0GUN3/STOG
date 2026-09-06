CREATE TABLE trip_invites (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX trip_invites_trip_created_idx
    ON trip_invites(trip_id, created_at DESC);

ALTER TABLE photos
    DROP CONSTRAINT photos_trip_id_fkey;

ALTER TABLE photos
    ALTER COLUMN trip_id DROP NOT NULL;

ALTER TABLE photos
    ADD CONSTRAINT photos_trip_id_fkey
        FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE SET NULL;
