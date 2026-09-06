ALTER TABLE refresh_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64);

ALTER TABLE oauth_login_tickets
    ALTER COLUMN token_hash TYPE VARCHAR(64);
