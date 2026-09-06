CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    provider TEXT NOT NULL,
    external_id TEXT NOT NULL,
    title TEXT NOT NULL,
    venue_name TEXT,
    formatted_address TEXT,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    detail_uri TEXT,
    image_uri TEXT,
    source_updated_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT events_provider_external_id_unique UNIQUE (provider, external_id),
    CONSTRAINT events_coordinates_check
        CHECK (
            latitude BETWEEN -90.0 AND 90.0
            AND longitude BETWEEN -180.0 AND 180.0
        ),
    CONSTRAINT events_date_range_check CHECK (ends_on >= starts_on),
    CONSTRAINT events_status_check CHECK (status IN ('active', 'retired'))
);

CREATE INDEX events_nearby_date_idx
    ON events (starts_on, ends_on, latitude, longitude)
    WHERE status = 'active';
