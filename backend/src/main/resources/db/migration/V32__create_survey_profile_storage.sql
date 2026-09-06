CREATE TABLE user_preferences (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    nature DOUBLE PRECISION NOT NULL CHECK (nature BETWEEN 0 AND 1),
    culture DOUBLE PRECISION NOT NULL CHECK (culture BETWEEN 0 AND 1),
    food DOUBLE PRECISION NOT NULL CHECK (food BETWEEN 0 AND 1),
    shopping DOUBLE PRECISION NOT NULL CHECK (shopping BETWEEN 0 AND 1),
    experience DOUBLE PRECISION NOT NULL CHECK (experience BETWEEN 0 AND 1),
    relaxation DOUBLE PRECISION NOT NULL CHECK (relaxation BETWEEN 0 AND 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_travel_styles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    localness DOUBLE PRECISION NOT NULL CHECK (localness BETWEEN 0 AND 1),
    crowd_tolerance DOUBLE PRECISION NOT NULL CHECK (crowd_tolerance BETWEEN 0 AND 1),
    pace DOUBLE PRECISION NOT NULL CHECK (pace BETWEEN 0 AND 1),
    spontaneity DOUBLE PRECISION NOT NULL CHECK (spontaneity BETWEEN 0 AND 1),
    activity_intensity DOUBLE PRECISION NOT NULL CHECK (activity_intensity BETWEEN 0 AND 1),
    novelty_seeking DOUBLE PRECISION NOT NULL CHECK (novelty_seeking BETWEEN 0 AND 1),
    travel_effort_tolerance DOUBLE PRECISION NOT NULL CHECK (travel_effort_tolerance BETWEEN 0 AND 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_survey_responses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    survey_version TEXT NOT NULL CHECK (survey_version = 'v1'),
    survey_type TEXT NOT NULL CHECK (survey_type IN ('cold_start', 'precision')),
    answers JSONB NOT NULL CHECK (jsonb_typeof(answers) = 'object'),
    preference_scores JSONB NOT NULL CHECK (jsonb_typeof(preference_scores) = 'object'),
    travel_style_scores JSONB NOT NULL CHECK (jsonb_typeof(travel_style_scores) = 'object'),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_survey_responses_version_type_unique
        UNIQUE (user_id, survey_version, survey_type)
);

CREATE INDEX user_survey_responses_user_completed_idx
    ON user_survey_responses(user_id, completed_at DESC);

INSERT INTO user_preferences (
    user_id, nature, culture, food, shopping, experience, relaxation
)
SELECT
    id,
    (preference_scores ->> 'nature')::double precision,
    (preference_scores ->> 'culture')::double precision,
    (preference_scores ->> 'food')::double precision,
    (preference_scores ->> 'shopping')::double precision,
    (preference_scores ->> 'experience')::double precision,
    (preference_scores ->> 'relaxation')::double precision
FROM users
WHERE preference_scores ?& ARRAY[
    'nature', 'culture', 'food', 'shopping', 'experience', 'relaxation'
]
  AND jsonb_typeof(preference_scores -> 'nature') = 'number'
  AND jsonb_typeof(preference_scores -> 'culture') = 'number'
  AND jsonb_typeof(preference_scores -> 'food') = 'number'
  AND jsonb_typeof(preference_scores -> 'shopping') = 'number'
  AND jsonb_typeof(preference_scores -> 'experience') = 'number'
  AND jsonb_typeof(preference_scores -> 'relaxation') = 'number'
  AND (preference_scores ->> 'nature')::double precision BETWEEN 0 AND 1
  AND (preference_scores ->> 'culture')::double precision BETWEEN 0 AND 1
  AND (preference_scores ->> 'food')::double precision BETWEEN 0 AND 1
  AND (preference_scores ->> 'shopping')::double precision BETWEEN 0 AND 1
  AND (preference_scores ->> 'experience')::double precision BETWEEN 0 AND 1
  AND (preference_scores ->> 'relaxation')::double precision BETWEEN 0 AND 1
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_travel_styles (
    user_id, localness, crowd_tolerance, pace, spontaneity,
    activity_intensity, novelty_seeking, travel_effort_tolerance
)
SELECT
    id,
    (travel_style_scores ->> 'localness')::double precision,
    (travel_style_scores ->> 'crowd_tolerance')::double precision,
    (travel_style_scores ->> 'pace')::double precision,
    (travel_style_scores ->> 'spontaneity')::double precision,
    (travel_style_scores ->> 'activity_intensity')::double precision,
    (travel_style_scores ->> 'novelty_seeking')::double precision,
    (travel_style_scores ->> 'travel_effort_tolerance')::double precision
FROM users
WHERE travel_style_scores ?& ARRAY[
    'localness', 'crowd_tolerance', 'pace', 'spontaneity',
    'activity_intensity', 'novelty_seeking', 'travel_effort_tolerance'
]
  AND jsonb_typeof(travel_style_scores -> 'localness') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'crowd_tolerance') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'pace') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'spontaneity') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'activity_intensity') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'novelty_seeking') = 'number'
  AND jsonb_typeof(travel_style_scores -> 'travel_effort_tolerance') = 'number'
  AND (travel_style_scores ->> 'localness')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'crowd_tolerance')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'pace')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'spontaneity')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'activity_intensity')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'novelty_seeking')::double precision BETWEEN 0 AND 1
  AND (travel_style_scores ->> 'travel_effort_tolerance')::double precision BETWEEN 0 AND 1
ON CONFLICT (user_id) DO NOTHING;
