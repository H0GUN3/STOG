ALTER TABLE users
    ADD COLUMN is_moderator BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE photos
    ADD COLUMN moderation_status TEXT NOT NULL DEFAULT 'pending',
    ADD CONSTRAINT photos_moderation_status_check
        CHECK (moderation_status IN ('pending', 'approved', 'blocked')),
    ADD CONSTRAINT photos_cell_coordinates_check
        CHECK (cell_id IS NULL OR (lat IS NOT NULL AND lng IS NOT NULL)),
    ADD CONSTRAINT photos_cell_id_id_unique UNIQUE (cell_id, id);

CREATE INDEX photos_moderation_visibility_idx
    ON photos(moderation_status, visibility);

CREATE INDEX photos_public_eligibility_cell_rank_idx
    ON photos(cell_id, like_count DESC, id)
    INCLUDE (trip_id)
    WHERE cell_id IS NOT NULL
      AND visibility = 'public'
      AND moderation_status = 'approved';

CREATE TABLE photo_public_grants (
    id BIGSERIAL PRIMARY KEY,
    photo_id BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    granted_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    scope TEXT[] NOT NULL DEFAULT ARRAY['feed', 'like', 'cell_featured', 'signed_read']::TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT photo_public_grants_photo_version_unique UNIQUE (photo_id, version),
    CONSTRAINT photo_public_grants_version_check CHECK (version > 0),
    CONSTRAINT photo_public_grants_revocation_time_check CHECK (
        revoked_at IS NULL OR revoked_at >= granted_at
    ),
    CONSTRAINT photo_public_grants_scope_check CHECK (
        cardinality(scope) > 0
        AND array_position(scope, NULL) IS NULL
        AND scope <@ ARRAY['feed', 'like', 'cell_featured', 'signed_read']::TEXT[]
    )
);

CREATE UNIQUE INDEX photo_public_grants_active_photo_unique
    ON photo_public_grants(photo_id)
    WHERE revoked_at IS NULL;

CREATE INDEX photo_public_grants_photo_revoked_idx
    ON photo_public_grants(photo_id, revoked_at);

CREATE INDEX photo_public_grants_photo_version_idx
    ON photo_public_grants(photo_id, version DESC);

CREATE TABLE photo_moderation_events (
    id BIGSERIAL PRIMARY KEY,
    photo_id BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
    moderator_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT photo_moderation_events_from_status_check CHECK (from_status = 'pending'),
    CONSTRAINT photo_moderation_events_to_status_check CHECK (
        to_status IN ('approved', 'blocked')
    ),
    CONSTRAINT photo_moderation_events_reason_check CHECK (
        reason IS NULL OR btrim(reason) <> ''
    )
);

CREATE INDEX photo_moderation_events_photo_created_idx
    ON photo_moderation_events(photo_id, created_at DESC);

CREATE INDEX photo_moderation_events_moderator_created_idx
    ON photo_moderation_events(moderator_id, created_at DESC);

CREATE OR REPLACE FUNCTION photo_moderation_events_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'photo_moderation_events are append-only';
END;
$$;

CREATE TRIGGER photo_moderation_events_append_only_trigger
BEFORE UPDATE OR DELETE ON photo_moderation_events
FOR EACH ROW
EXECUTE FUNCTION photo_moderation_events_reject_mutation();

CREATE TABLE likes (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, target_type, target_id),
    CONSTRAINT likes_target_type_check CHECK (target_type IN ('trip', 'photo')),
    CONSTRAINT likes_target_id_check CHECK (target_id > 0)
);

CREATE INDEX likes_target_idx ON likes(target_type, target_id);

CREATE TABLE cell_stats (
    cell_id BIGINT PRIMARY KEY,
    landmark_count BIGINT NOT NULL DEFAULT 0,
    public_photo_count BIGINT NOT NULL DEFAULT 0,
    public_photo_like_count BIGINT NOT NULL DEFAULT 0,
    top_photo_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cell_stats_landmark_count_check CHECK (landmark_count >= 0),
    CONSTRAINT cell_stats_public_photo_count_check CHECK (public_photo_count >= 0),
    CONSTRAINT cell_stats_public_photo_like_count_check CHECK (public_photo_like_count >= 0),
    CONSTRAINT cell_stats_nonempty_projection_check CHECK (
        landmark_count > 0 OR public_photo_count > 0
    ),
    CONSTRAINT cell_stats_like_count_requires_public_photo_check CHECK (
        public_photo_count > 0 OR public_photo_like_count = 0
    ),
    CONSTRAINT cell_stats_top_photo_requires_public_photo_check CHECK (
        top_photo_id IS NULL OR public_photo_count > 0
    ),
    CONSTRAINT cell_stats_top_photo_same_cell_fk
        FOREIGN KEY (cell_id, top_photo_id)
        REFERENCES photos(cell_id, id)
        ON DELETE RESTRICT
);

CREATE INDEX cell_stats_public_photo_count_idx
    ON cell_stats(public_photo_count DESC);

CREATE INDEX cell_stats_public_photo_like_count_idx
    ON cell_stats(public_photo_like_count DESC);

CREATE INDEX places_public_catalog_cell_idx
    ON places(cell_id)
    WHERE catalog_status = 'public' AND cell_id IS NOT NULL;

-- Public eligibility, polymorphic target validation, and projection cache updates
-- are service-layer transactional responsibilities. This migration only enforces
-- same-row invariants and the top-photo same-cell reference.
