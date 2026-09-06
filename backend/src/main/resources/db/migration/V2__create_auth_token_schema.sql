CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_id BIGINT REFERENCES refresh_tokens(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX refresh_tokens_user_expires_idx
    ON refresh_tokens(user_id, expires_at);

CREATE TABLE oauth_login_tickets (
    id BIGSERIAL PRIMARY KEY,
    provider TEXT NOT NULL CHECK (provider = 'naver'),
    provider_user_id TEXT NOT NULL,
    nickname TEXT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
