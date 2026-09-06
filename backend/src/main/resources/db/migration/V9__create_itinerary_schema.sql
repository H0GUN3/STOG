ALTER TABLE basket_items
    ADD CONSTRAINT basket_items_trip_id_id_unique UNIQUE (trip_id, id);

CREATE TABLE itinerary_items (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    basket_item_id BIGINT NOT NULL,
    day_number INTEGER NOT NULL CHECK (day_number > 0),
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    planned_arrival TEXT,
    planned_duration_min INTEGER
        CHECK (planned_duration_min IS NULL OR planned_duration_min > 0),
    CONSTRAINT itinerary_items_trip_basket_item_fk
        FOREIGN KEY (trip_id, basket_item_id)
        REFERENCES basket_items(trip_id, id)
        ON DELETE CASCADE,
    CONSTRAINT itinerary_items_trip_basket_item_unique
        UNIQUE (trip_id, basket_item_id),
    CONSTRAINT itinerary_items_trip_day_order_unique
        UNIQUE (trip_id, day_number, order_index)
);

CREATE TABLE itinerary_changes (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX itinerary_changes_trip_created_idx
    ON itinerary_changes(trip_id, created_at DESC);
