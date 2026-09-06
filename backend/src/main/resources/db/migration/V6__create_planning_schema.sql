CREATE TABLE trips (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    activity_type TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'dormant'
        CHECK (mode IN ('active', 'dormant', 'ended')),
    visibility TEXT NOT NULL DEFAULT 'private'
        CHECK (visibility IN ('private', 'group', 'public')),
    is_group BOOLEAN NOT NULL DEFAULT FALSE,
    planned_start_date DATE,
    planned_end_date DATE,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT trips_date_range_check CHECK (
        planned_end_date IS NULL
        OR planned_start_date IS NULL
        OR planned_end_date >= planned_start_date
    )
);

CREATE INDEX trips_owner_id_idx ON trips(owner_id);

CREATE TABLE places (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT,
    lat DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    cell_id BIGINT,
    source TEXT NOT NULL
        CHECK (source IN ('google', 'kakao', 'naver', 'public_data', 'user')),
    external_id TEXT,
    accessibility JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT places_source_external_id_unique UNIQUE (source, external_id)
);

CREATE INDEX places_cell_id_idx ON places(cell_id);
CREATE INDEX places_coords_idx ON places(lat, lng);

CREATE TABLE basket_items (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    added_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    item_type TEXT NOT NULL
        CHECK (item_type IN ('place', 'link')),
    place_id BIGINT REFERENCES places(id) ON DELETE RESTRICT,
    source TEXT,
    original_url TEXT,
    title TEXT,
    thumbnail_url TEXT,
    category TEXT,
    lat DOUBLE PRECISION CHECK (lat IS NULL OR lat BETWEEN -90 AND 90),
    lng DOUBLE PRECISION CHECK (lng IS NULL OR lng BETWEEN -180 AND 180),
    cell_id BIGINT,
    status TEXT NOT NULL DEFAULT 'unresolved'
        CHECK (status IN ('resolved', 'manual', 'unresolved')),
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT basket_items_type_payload_check CHECK (
        (item_type = 'place' AND place_id IS NOT NULL)
        OR (item_type = 'link' AND original_url IS NOT NULL)
    )
);

CREATE INDEX basket_items_trip_status_idx ON basket_items(trip_id, status);
CREATE INDEX basket_items_trip_added_by_idx ON basket_items(trip_id, added_by);
