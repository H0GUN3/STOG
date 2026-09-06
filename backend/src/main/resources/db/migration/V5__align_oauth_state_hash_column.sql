ALTER TABLE oauth_states
    ALTER COLUMN state_hash TYPE VARCHAR(64);
