ALTER TABLE itinerary_changes
    DROP CONSTRAINT itinerary_changes_action_check,
    ADD CONSTRAINT itinerary_changes_action_check
        CHECK (action IN ('replace', 'copy_public_trip'));

CREATE TABLE public_trip_copy_receipts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL CHECK (btrim(idempotency_key) <> ''),
    source_trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE RESTRICT,
    destination_trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE RESTRICT,
    destination_day INTEGER NOT NULL CHECK (destination_day > 0),
    payload_fingerprint CHAR(64) NOT NULL
        CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    copied_item_count INTEGER NOT NULL CHECK (copied_item_count >= 0),
    destination_basket_item_ids BIGINT[] NOT NULL,
    destination_itinerary_item_ids BIGINT[] NOT NULL,
    itinerary_change_id BIGINT NOT NULL REFERENCES itinerary_changes(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT public_trip_copy_receipts_user_key_unique
        UNIQUE (user_id, idempotency_key),
    CONSTRAINT public_trip_copy_receipts_count_check CHECK (
        copied_item_count = cardinality(destination_basket_item_ids)
        AND copied_item_count = cardinality(destination_itinerary_item_ids)
    )
);

CREATE INDEX public_trip_copy_receipts_source_trip_idx
    ON public_trip_copy_receipts(source_trip_id);
CREATE INDEX public_trip_copy_receipts_destination_trip_idx
    ON public_trip_copy_receipts(destination_trip_id);

CREATE FUNCTION public_trip_copy_receipts_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'public_trip_copy_receipts are immutable';
END;
$$;

CREATE TRIGGER public_trip_copy_receipts_immutable
BEFORE UPDATE OR DELETE ON public_trip_copy_receipts
FOR EACH ROW EXECUTE FUNCTION public_trip_copy_receipts_reject_mutation();

GRANT SELECT, INSERT ON public_trip_copy_receipts TO stog_app;
GRANT USAGE, SELECT ON SEQUENCE public_trip_copy_receipts_id_seq TO stog_app;
