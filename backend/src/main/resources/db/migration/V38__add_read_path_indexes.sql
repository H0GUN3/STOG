CREATE INDEX trips_public_ended_cursor_idx
    ON trips (ended_at DESC, id DESC)
    WHERE visibility = 'public'
      AND mode = 'ended'
      AND ended_at IS NOT NULL;

CREATE INDEX photos_feed_cursor_idx
    ON photos (created_at DESC, id DESC)
    WHERE visibility = 'public'
      AND moderation_status = 'approved';

CREATE INDEX visits_trip_member_entered_idx
    ON visits (trip_id, user_id, entered_at, id);

CREATE INDEX visits_user_status_cell_idx
    ON visits (user_id, status, is_interpolated, cell_id);

CREATE INDEX photos_user_created_idx
    ON photos (user_id, created_at DESC);
