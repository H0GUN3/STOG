INSERT INTO trip_members (trip_id, user_id, joined_at)
SELECT id, owner_id, created_at
FROM trips
ON CONFLICT (trip_id, user_id) DO NOTHING;

CREATE TABLE trip_member_collection_states (
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    collector_state TEXT NOT NULL DEFAULT 'inactive'
        CHECK (collector_state IN ('inactive', 'starting', 'active', 'dormant', 'blocked', 'ended')),
    permission_state TEXT NOT NULL DEFAULT 'unknown'
        CHECK (permission_state IN ('unknown', 'granted', 'denied', 'revoked')),
    sync_cursor TEXT,
    mode_version BIGINT NOT NULL DEFAULT 0 CHECK (mode_version >= 0),
    collector_started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (trip_id, user_id),
    CONSTRAINT trip_member_collection_states_member_fk
        FOREIGN KEY (trip_id, user_id)
        REFERENCES trip_members(trip_id, user_id)
        ON DELETE CASCADE
);

INSERT INTO trip_member_collection_states (trip_id, user_id)
SELECT trip_id, user_id
FROM trip_members
WHERE left_at IS NULL
ON CONFLICT (trip_id, user_id) DO NOTHING;

ALTER TABLE visits
    ADD COLUMN client_visit_id UUID,
    ADD COLUMN payload_fingerprint VARCHAR(64),
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE visits
SET client_visit_id = gen_random_uuid(),
    payload_fingerprint = md5(
        concat_ws(
            '|',
            id::text,
            trip_id::text,
            user_id::text,
            cell_id::text,
            entered_at::text,
            left_at::text
        )
    ) || md5(
        concat_ws(
            '|',
            'legacy',
            id::text,
            status,
            is_interpolated::text
        )
    );

ALTER TABLE visits
    ALTER COLUMN client_visit_id SET NOT NULL,
    ALTER COLUMN payload_fingerprint SET NOT NULL,
    ADD CONSTRAINT visits_payload_fingerprint_check CHECK (
        payload_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT visits_member_client_visit_unique
        UNIQUE (trip_id, user_id, client_visit_id);

CREATE OR REPLACE FUNCTION visits_reject_idempotency_identity_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.client_visit_id IS DISTINCT FROM OLD.client_visit_id
        OR NEW.payload_fingerprint IS DISTINCT FROM OLD.payload_fingerprint
    THEN
        RAISE EXCEPTION 'visit idempotency identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER visits_idempotency_identity_immutable_trigger
BEFORE UPDATE OF client_visit_id, payload_fingerprint ON visits
FOR EACH ROW
EXECUTE FUNCTION visits_reject_idempotency_identity_mutation();
