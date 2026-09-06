CREATE TABLE source_user_mappings (
    source_user_uuid UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX source_user_mappings_user_id_idx
    ON source_user_mappings(user_id);
