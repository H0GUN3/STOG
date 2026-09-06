CREATE TABLE photo_comments (
    id BIGSERIAL PRIMARY KEY,
    photo_id BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT photo_comments_body_check CHECK (
        btrim(body) <> '' AND char_length(body) <= 500
    )
);

CREATE INDEX photo_comments_photo_created_idx
    ON photo_comments(photo_id, created_at, id);
