ALTER TABLE basket_items
    ADD COLUMN client_item_id TEXT,
    ADD COLUMN payload_fingerprint TEXT,
    ADD COLUMN provider TEXT,
    ADD COLUMN external_id TEXT,
    ADD COLUMN source_record_id BIGINT,
    ADD COLUMN source_type TEXT,
    ADD COLUMN source_label TEXT,
    ADD COLUMN confidence DOUBLE PRECISION,
    ADD COLUMN address TEXT;

UPDATE basket_items
SET client_item_id = 'legacy-' || id,
    payload_fingerprint = repeat('0', 64),
    provider = source,
    source_type = item_type,
    source_label = source,
    confidence = 1.0
WHERE client_item_id IS NULL;

ALTER TABLE basket_items
    ALTER COLUMN client_item_id SET NOT NULL,
    ALTER COLUMN payload_fingerprint SET NOT NULL,
    ADD CONSTRAINT basket_items_payload_fingerprint_format_check
        CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT basket_items_trip_user_client_item_unique
        UNIQUE (trip_id, added_by, client_item_id);

CREATE FUNCTION basket_item_idempotency_is_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.trip_id IS DISTINCT FROM OLD.trip_id
       OR NEW.added_by IS DISTINCT FROM OLD.added_by
       OR NEW.client_item_id IS DISTINCT FROM OLD.client_item_id
       OR NEW.payload_fingerprint IS DISTINCT FROM OLD.payload_fingerprint
       OR NEW.item_type IS DISTINCT FROM OLD.item_type
       OR NEW.place_id IS DISTINCT FROM OLD.place_id
       OR NEW.source IS DISTINCT FROM OLD.source
       OR NEW.original_url IS DISTINCT FROM OLD.original_url
       OR NEW.title IS DISTINCT FROM OLD.title
       OR NEW.thumbnail_url IS DISTINCT FROM OLD.thumbnail_url
       OR NEW.category IS DISTINCT FROM OLD.category
       OR NEW.lat IS DISTINCT FROM OLD.lat
       OR NEW.lng IS DISTINCT FROM OLD.lng
       OR NEW.cell_id IS DISTINCT FROM OLD.cell_id
       OR NEW.added_at IS DISTINCT FROM OLD.added_at
       OR NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.external_id IS DISTINCT FROM OLD.external_id
       OR NEW.source_record_id IS DISTINCT FROM OLD.source_record_id
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_label IS DISTINCT FROM OLD.source_label
       OR NEW.confidence IS DISTINCT FROM OLD.confidence
       OR NEW.address IS DISTINCT FROM OLD.address THEN
        RAISE EXCEPTION 'basket item idempotency identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER basket_item_idempotency_immutable
BEFORE UPDATE ON basket_items
FOR EACH ROW
EXECUTE FUNCTION basket_item_idempotency_is_immutable();
