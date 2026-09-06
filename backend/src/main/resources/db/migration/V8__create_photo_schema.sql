CREATE TABLE photos (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    source TEXT NOT NULL
        CHECK (source IN ('camera', 'gallery')),
    cell_id BIGINT,
    lat DOUBLE PRECISION
        CHECK (lat IS NULL OR lat BETWEEN -90 AND 90),
    lng DOUBLE PRECISION
        CHECK (lng IS NULL OR lng BETWEEN -180 AND 180),
    taken_at TIMESTAMPTZ,
    original_key TEXT NOT NULL UNIQUE,
    thumb_key TEXT NOT NULL UNIQUE,
    caption TEXT,
    like_count BIGINT NOT NULL DEFAULT 0
        CHECK (like_count >= 0),
    visibility TEXT NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'group', 'public')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT photos_coordinate_pair_check CHECK (
        (lat IS NULL AND lng IS NULL)
        OR (lat IS NOT NULL AND lng IS NOT NULL)
    )
);

CREATE INDEX photos_cell_like_idx
    ON photos(cell_id, like_count DESC);
CREATE INDEX photos_trip_idx ON photos(trip_id);
CREATE INDEX photos_user_idx ON photos(user_id);
