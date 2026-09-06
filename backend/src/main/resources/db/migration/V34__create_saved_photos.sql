CREATE TABLE saved_photos (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    photo_id BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, photo_id)
);

CREATE INDEX saved_photos_user_created_photo_idx
    ON saved_photos(user_id, created_at DESC, photo_id DESC);
