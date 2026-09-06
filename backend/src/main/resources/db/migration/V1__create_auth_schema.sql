CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    nickname TEXT NOT NULL,
    traveler_type TEXT,
    axis_scores JSONB,
    mobility_style TEXT,
    honey_balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider TEXT NOT NULL
        CHECK (provider IN ('local', 'google', 'kakao', 'naver')),
    provider_user_id TEXT NOT NULL,
    password_hash TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_accounts_password_check CHECK (
        (provider = 'local' AND password_hash IS NOT NULL)
        OR (provider <> 'local' AND password_hash IS NULL)
    ),
    CONSTRAINT auth_accounts_provider_user_id_unique
        UNIQUE (provider, provider_user_id)
);

CREATE INDEX auth_accounts_user_id_idx ON auth_accounts(user_id);
