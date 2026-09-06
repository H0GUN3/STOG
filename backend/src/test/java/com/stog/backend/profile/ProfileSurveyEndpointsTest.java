package com.stog.backend.profile;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileSurveyEndpointsTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void existingProfileUpdateAlsoPopulatesCanonicalScoreTables() throws Exception {
        long userId = user("profile-compatibility");

        mockMvc.perform(put("/profile")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "preference_scores": {
                        "nature": 1.0, "culture": 0.5, "food": 0.75,
                        "shopping": 0.25, "experience": 0.5, "relaxation": 0.0
                      },
                      "travel_style_scores": {
                        "localness": 0.75, "crowd_tolerance": 0.5, "pace": 0.25,
                        "spontaneity": 0.5, "activity_intensity": 0.75,
                        "novelty_seeking": 1.0, "travel_effort_tolerance": 0.0
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preference_scores.food").value(0.75))
            .andExpect(jsonPath("$.travel_style_scores.localness").value(0.75));

        Integer preferenceRows = jdbc.sql("""
                SELECT COUNT(*) FROM user_preferences WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query(Integer.class)
            .single();
        Integer travelStyleRows = jdbc.sql("""
                SELECT COUNT(*) FROM user_travel_styles WHERE user_id = :userId
                """)
            .param("userId", userId)
            .query(Integer.class)
            .single();
        org.assertj.core.api.Assertions.assertThat(preferenceRows).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(travelStyleRows).isEqualTo(1);
    }

    @Test
    void coldStartIsStoredAndPrecisionReplacesItsCanonicalProfile() throws Exception {
        long userId = user("survey-owner");

        mockMvc.perform(put("/profile/survey")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "survey_version": "v1",
                      "survey_type": "cold_start",
                      "answers": {
                        "q1": "food",
                        "q2": "local",
                        "q3": "packed",
                        "q4": "spontaneous",
                        "q5": "active"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonical").value(false))
            .andExpect(jsonPath("$.preference_scores.food").value(0.75))
            .andExpect(jsonPath("$.travel_style_scores.localness").value(0.75));

        mockMvc.perform(put("/profile/survey")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "survey_version": "v1",
                      "survey_type": "precision",
                      "answers": {
                        "q1": 5, "q2": 3, "q3": 5, "q4": 2, "q5": 4, "q6": 4,
                        "q7": 2, "q8": 3, "q9": 4, "q10": 2, "q11": 3,
                        "q12": 5, "q13": 4
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canonical").value(true))
            .andExpect(jsonPath("$.preference_scores.food").value(1.0))
            .andExpect(jsonPath("$.travel_style_scores.localness").value(0.75));

        mockMvc.perform(get("/profile")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preference_scores.food").value(1.0))
            .andExpect(jsonPath("$.travel_style_scores.localness").value(0.75));

        Integer precisionResponses = jdbc.sql("""
                SELECT COUNT(*)
                FROM user_survey_responses
                WHERE user_id = :userId
                  AND survey_type = 'precision'
                """)
            .param("userId", userId)
            .query(Integer.class)
            .single();
        org.assertj.core.api.Assertions.assertThat(precisionResponses).isEqualTo(1);
    }

    @Test
    void coldStartCannotReplaceAnAlreadyCanonicalPrecisionProfile() throws Exception {
        long userId = user("survey-locked");
        submitPrecision(userId);

        mockMvc.perform(put("/profile/survey")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "survey_version": "v1",
                      "survey_type": "cold_start",
                      "answers": {
                        "q1": "nature",
                        "q2": "representative",
                        "q3": "leisurely",
                        "q4": "planned",
                        "q5": "comfortable"
                      }
                    }
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    void precisionAnswersMustBeWithinTheDeclaredScale() throws Exception {
        long userId = user("survey-validation");

        mockMvc.perform(put("/profile/survey")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "survey_version": "v1",
                      "survey_type": "precision",
                      "answers": {
                        "q1": 6, "q2": 3, "q3": 3, "q4": 3, "q5": 3, "q6": 3,
                        "q7": 3, "q8": 3, "q9": 3, "q10": 3, "q11": 3,
                        "q12": 3, "q13": 3
                      }
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    private void submitPrecision(long userId) throws Exception {
        mockMvc.perform(put("/profile/survey")
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "survey_version": "v1",
                      "survey_type": "precision",
                      "answers": {
                        "q1": 3, "q2": 3, "q3": 3, "q4": 3, "q5": 3, "q6": 3,
                        "q7": 3, "q8": 3, "q9": 3, "q10": 3, "q11": 3,
                        "q12": 3, "q13": 3
                      }
                    }
                    """))
            .andExpect(status().isOk());
    }

    private long user(String nickname) {
        return jdbc.sql("""
                INSERT INTO users (nickname)
                VALUES (:nickname)
                RETURNING id
                """)
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }
}
