DO $$
DECLARE
    itinerary_item_record RECORD;
BEGIN
    FOR itinerary_item_record IN
        SELECT id, planned_arrival
        FROM itinerary_items
        WHERE planned_arrival IS NOT NULL
    LOOP
        BEGIN
            PERFORM itinerary_item_record.planned_arrival::TIME;
        EXCEPTION
            WHEN invalid_datetime_format OR datetime_field_overflow THEN
                RAISE EXCEPTION
                    'itinerary_items.planned_arrival for id % is not convertible to TIME',
                    itinerary_item_record.id;
        END;
    END LOOP;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users
        WHERE mobility_style IS NOT NULL
          AND mobility_style NOT IN ('한 지역 도보', '대중교통 광역', '자차 원거리', '한 장소 체류')
    ) THEN
        RAISE EXCEPTION 'users.mobility_style contains a value outside the canonical mobility_style glossary';
    END IF;
END
$$;

CREATE TABLE trip_members (
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (trip_id, user_id),
    CONSTRAINT trip_members_membership_period_check CHECK (
        left_at IS NULL OR left_at >= joined_at
    )
);

CREATE INDEX trip_members_active_trip_user_idx
    ON trip_members(trip_id, user_id)
    WHERE left_at IS NULL;

CREATE TABLE user_constraints (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    constraint_type TEXT NOT NULL,
    constraint_code TEXT NOT NULL,
    PRIMARY KEY (user_id, constraint_type, constraint_code),
    CONSTRAINT user_constraints_type_code_check CHECK (
        constraint_type = 'mobility_style'
        AND constraint_code IN ('한 지역 도보', '대중교통 광역', '자차 원거리', '한 장소 체류')
    )
);

INSERT INTO user_constraints (user_id, constraint_type, constraint_code)
SELECT id, 'mobility_style', mobility_style
FROM users
WHERE mobility_style IS NOT NULL;

CREATE TABLE visits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    cell_id BIGINT NOT NULL,
    lat DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    entered_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('passed', 'visited')),
    is_interpolated BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT visits_h3_resolution_check CHECK (
        cell_id > 0 AND ((cell_id >> 52) & 15) = 10
    ),
    CONSTRAINT visits_time_range_check CHECK (left_at >= entered_at),
    CONSTRAINT visits_interpolated_status_check CHECK (
        NOT is_interpolated OR status = 'passed'
    )
);

CREATE INDEX visits_cell_id_idx ON visits(cell_id);
CREATE INDEX visits_trip_entered_at_idx ON visits(trip_id, entered_at);

ALTER TABLE trips
    ADD CONSTRAINT trips_activity_type_check
    CHECK (activity_type IN ('walk', 'run', 'date', 'tour', 'etc'));

ALTER TABLE itinerary_items
    ALTER COLUMN planned_arrival TYPE TIME
    USING planned_arrival::TIME;

ALTER TABLE itinerary_changes
    ADD CONSTRAINT itinerary_changes_action_check
    CHECK (action IN ('replace'));
