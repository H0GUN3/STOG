package com.stog.backend.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileSurveyRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public ProfileSurveyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(long userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
            .param("userId", userId)
            .query(Long.class)
            .optional();
    }

    public boolean hasPrecisionSurvey(long userId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1
                    FROM user_survey_responses
                    WHERE user_id = :userId
                      AND survey_version = 'v1'
                      AND survey_type = 'precision'
                )
                """)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public Optional<Map<String, Double>> findPreferences(long userId) {
        return jdbc.sql("""
                SELECT nature, culture, food, shopping, experience, relaxation
                FROM user_preferences
                WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query((row, rowNumber) -> {
                Map<String, Double> values = new LinkedHashMap<>();
                values.put("nature", row.getDouble("nature"));
                values.put("culture", row.getDouble("culture"));
                values.put("food", row.getDouble("food"));
                values.put("shopping", row.getDouble("shopping"));
                values.put("experience", row.getDouble("experience"));
                values.put("relaxation", row.getDouble("relaxation"));
                return Map.copyOf(values);
            })
            .optional();
    }

    public Optional<Map<String, Double>> findTravelStyles(long userId) {
        return jdbc.sql("""
                SELECT localness, crowd_tolerance, pace, spontaneity,
                       activity_intensity, novelty_seeking, travel_effort_tolerance
                FROM user_travel_styles
                WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query((row, rowNumber) -> {
                Map<String, Double> values = new LinkedHashMap<>();
                values.put("localness", row.getDouble("localness"));
                values.put("crowd_tolerance", row.getDouble("crowd_tolerance"));
                values.put("pace", row.getDouble("pace"));
                values.put("spontaneity", row.getDouble("spontaneity"));
                values.put("activity_intensity", row.getDouble("activity_intensity"));
                values.put("novelty_seeking", row.getDouble("novelty_seeking"));
                values.put("travel_effort_tolerance", row.getDouble("travel_effort_tolerance"));
                return Map.copyOf(values);
            })
            .optional();
    }

    public void saveSurvey(
        long userId,
        String surveyVersion,
        String surveyType,
        Map<String, Object> answers,
        SurveyProfileScorer.Result result
    ) {
        jdbc.sql("""
                INSERT INTO user_survey_responses (
                    user_id, survey_version, survey_type, answers,
                    preference_scores, travel_style_scores
                )
                VALUES (
                    :userId, :surveyVersion, :surveyType,
                    CAST(:answers AS jsonb),
                    CAST(:preferenceScores AS jsonb),
                    CAST(:travelStyleScores AS jsonb)
                )
                ON CONFLICT (user_id, survey_version, survey_type)
                DO UPDATE SET
                    answers = EXCLUDED.answers,
                    preference_scores = EXCLUDED.preference_scores,
                    travel_style_scores = EXCLUDED.travel_style_scores,
                    completed_at = CURRENT_TIMESTAMP
                """)
            .params(Map.of(
                "userId", userId,
                "surveyVersion", surveyVersion,
                "surveyType", surveyType,
                "answers", write(answers),
                "preferenceScores", write(result.preference()),
                "travelStyleScores", write(result.travelStyle())
            ))
            .update();
        replaceScores(userId, result.preference(), result.travelStyle());
    }

    public void replaceScores(
        long userId,
        Map<String, Double> preference,
        Map<String, Double> travelStyle
    ) {
        if (preference != null) {
            jdbc.sql("""
                    INSERT INTO user_preferences (
                        user_id, nature, culture, food, shopping, experience, relaxation
                    )
                    VALUES (
                        :userId, :nature, :culture, :food,
                        :shopping, :experience, :relaxation
                    )
                    ON CONFLICT (user_id)
                    DO UPDATE SET
                        nature = EXCLUDED.nature,
                        culture = EXCLUDED.culture,
                        food = EXCLUDED.food,
                        shopping = EXCLUDED.shopping,
                        experience = EXCLUDED.experience,
                        relaxation = EXCLUDED.relaxation,
                        updated_at = CURRENT_TIMESTAMP
                    """)
                .params(preferenceParams(userId, preference))
                .update();
        }
        if (travelStyle != null) {
            jdbc.sql("""
                    INSERT INTO user_travel_styles (
                        user_id, localness, crowd_tolerance, pace, spontaneity,
                        activity_intensity, novelty_seeking, travel_effort_tolerance
                    )
                    VALUES (
                        :userId, :localness, :crowdTolerance, :pace, :spontaneity,
                        :activityIntensity, :noveltySeeking, :travelEffortTolerance
                    )
                    ON CONFLICT (user_id)
                    DO UPDATE SET
                        localness = EXCLUDED.localness,
                        crowd_tolerance = EXCLUDED.crowd_tolerance,
                        pace = EXCLUDED.pace,
                        spontaneity = EXCLUDED.spontaneity,
                        activity_intensity = EXCLUDED.activity_intensity,
                        novelty_seeking = EXCLUDED.novelty_seeking,
                        travel_effort_tolerance = EXCLUDED.travel_effort_tolerance,
                        updated_at = CURRENT_TIMESTAMP
                    """)
                .params(travelStyleParams(userId, travelStyle))
                .update();
        }
    }

    private static Map<String, Object> preferenceParams(
        long userId,
        Map<String, Double> values
    ) {
        return Map.of(
            "userId", userId,
            "nature", values.get("nature"),
            "culture", values.get("culture"),
            "food", values.get("food"),
            "shopping", values.get("shopping"),
            "experience", values.get("experience"),
            "relaxation", values.get("relaxation")
        );
    }

    private static Map<String, Object> travelStyleParams(
        long userId,
        Map<String, Double> values
    ) {
        return Map.of(
            "userId", userId,
            "localness", values.get("localness"),
            "crowdTolerance", values.get("crowd_tolerance"),
            "pace", values.get("pace"),
            "spontaneity", values.get("spontaneity"),
            "activityIntensity", values.get("activity_intensity"),
            "noveltySeeking", values.get("novelty_seeking"),
            "travelEffortTolerance", values.get("travel_effort_tolerance")
        );
    }

    private String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Survey profile is not valid JSON", error);
        }
    }
}
