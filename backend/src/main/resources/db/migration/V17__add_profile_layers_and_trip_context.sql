ALTER TABLE users
    ADD COLUMN preference_scores JSONB,
    ADD COLUMN travel_style_scores JSONB;

ALTER TABLE trips
    ADD COLUMN trip_context JSONB;

CREATE TABLE profile_evidence (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    trip_id BIGINT REFERENCES trips(id) ON DELETE SET NULL,
    evidence_type TEXT NOT NULL
        CHECK (evidence_type IN ('preference', 'travel_style', 'trip_context')),
    source TEXT NOT NULL
        CHECK (source IN ('onboarding', 'behavior', 'conversation')),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT profile_evidence_trip_context_trip_check CHECK (
        evidence_type <> 'trip_context' OR trip_id IS NOT NULL
    )
);

CREATE INDEX profile_evidence_user_created_idx
    ON profile_evidence(user_id, created_at DESC);

CREATE INDEX profile_evidence_user_type_created_idx
    ON profile_evidence(user_id, evidence_type, created_at DESC);

CREATE INDEX profile_evidence_trip_idx
    ON profile_evidence(trip_id);
