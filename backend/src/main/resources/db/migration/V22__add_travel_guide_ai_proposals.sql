ALTER TABLE itinerary_items
    ADD COLUMN is_fixed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE trip_itinerary_states (
    trip_id BIGINT PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

INSERT INTO trip_itinerary_states (trip_id, version)
SELECT trip.id, COUNT(change.id)
FROM trips trip
LEFT JOIN itinerary_changes change ON change.trip_id = trip.id
GROUP BY trip.id;

CREATE TABLE travel_guide_ai_proposals (
    id UUID PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    base_version BIGINT NOT NULL CHECK (base_version >= 0),
    proposal_fingerprint CHAR(64) NOT NULL,
    feasible BOOLEAN NOT NULL,
    status TEXT NOT NULL DEFAULT 'ready' CHECK (status IN ('ready', 'invalid', 'applied')),
    applied_client_id UUID,
    applied_payload_fingerprint CHAR(64),
    applied_change_id BIGINT REFERENCES itinerary_changes(id) ON DELETE CASCADE,
    applied_version BIGINT CHECK (applied_version IS NULL OR applied_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ,
    CONSTRAINT travel_guide_ai_proposals_apply_fields CHECK (
        (status <> 'applied' AND applied_client_id IS NULL AND applied_payload_fingerprint IS NULL
            AND applied_change_id IS NULL AND applied_version IS NULL AND applied_at IS NULL)
        OR
        (status = 'applied' AND applied_client_id IS NOT NULL AND applied_payload_fingerprint IS NOT NULL
            AND applied_change_id IS NOT NULL AND applied_version IS NOT NULL AND applied_at IS NOT NULL)
    )
);

CREATE INDEX travel_guide_ai_proposals_trip_created_idx
    ON travel_guide_ai_proposals(trip_id, created_at DESC, id);

CREATE TABLE travel_guide_ai_proposal_actions (
    proposal_id UUID NOT NULL REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE,
    action_order INTEGER NOT NULL CHECK (action_order >= 0),
    basket_item_id BIGINT NOT NULL,
    day_number INTEGER NOT NULL CHECK (day_number > 0),
    order_index INTEGER NOT NULL CHECK (order_index >= 0),
    planned_arrival TIME NOT NULL,
    planned_duration_min INTEGER NOT NULL CHECK (planned_duration_min > 0),
    travel_minutes_from_previous INTEGER NOT NULL CHECK (travel_minutes_from_previous >= 0),
    is_fixed BOOLEAN NOT NULL,
    PRIMARY KEY (proposal_id, action_order)
);

CREATE TABLE travel_guide_ai_proposal_exclusions (
    proposal_id UUID NOT NULL REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE,
    exclusion_order INTEGER NOT NULL CHECK (exclusion_order >= 0),
    basket_item_id BIGINT NOT NULL,
    PRIMARY KEY (proposal_id, exclusion_order)
);

CREATE TABLE travel_guide_ai_proposal_violations (
    proposal_id UUID NOT NULL REFERENCES travel_guide_ai_proposals(id) ON DELETE CASCADE,
    violation_order INTEGER NOT NULL CHECK (violation_order >= 0),
    code TEXT NOT NULL,
    basket_item_id BIGINT,
    PRIMARY KEY (proposal_id, violation_order)
);

