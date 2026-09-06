package com.stog.backend.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurveyProfileScorerTest {
    private final SurveyProfileScorer scorer = new SurveyProfileScorer();

    @Test
    void precisionSurveyUsesForwardAndReverseNormalization() {
        Map<String, Object> answers = answers(
            "q1", 5, "q2", 3, "q3", 5, "q4", 2, "q5", 4, "q6", 4,
            "q7", 2, "q8", 3, "q9", 4, "q10", 2, "q11", 3, "q12", 5, "q13", 4
        );

        SurveyProfileScorer.Result result = scorer.score("precision", answers);

        assertThat(result.preference()).containsEntry("nature", 1.0)
            .containsEntry("culture", 0.5)
            .containsEntry("food", 1.0)
            .containsEntry("shopping", 0.25)
            .containsEntry("experience", 0.75)
            .containsEntry("relaxation", 0.75);
        assertThat(result.travelStyle()).containsEntry("localness", 0.75)
            .containsEntry("crowd_tolerance", 0.5)
            .containsEntry("pace", 0.75)
            .containsEntry("spontaneity", 0.75)
            .containsEntry("activity_intensity", 0.5)
            .containsEntry("novelty_seeking", 1.0)
            .containsEntry("travel_effort_tolerance", 0.75);
        assertThat(result.canonical()).isTrue();
    }

    @Test
    void coldStartUsesNeutralDefaultsAndSelectedValues() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("q1", "food");
        answers.put("q2", "local");
        answers.put("q3", "packed");
        answers.put("q4", "spontaneous");
        answers.put("q5", "active");

        SurveyProfileScorer.Result result = scorer.score("cold_start", answers);

        assertThat(result.preference()).containsEntry("food", 0.75)
            .containsEntry("nature", 0.5);
        assertThat(result.travelStyle()).containsEntry("localness", 0.75)
            .containsEntry("pace", 0.75)
            .containsEntry("spontaneity", 0.75)
            .containsEntry("activity_intensity", 0.75)
            .containsEntry("crowd_tolerance", 0.5)
            .containsEntry("novelty_seeking", 0.5)
            .containsEntry("travel_effort_tolerance", 0.5);
        assertThat(result.canonical()).isFalse();
    }

    @Test
    void rejectsMissingExtraAndOutOfRangeAnswers() {
        assertThatThrownBy(() -> scorer.score("precision", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("q1");
        assertThatThrownBy(() -> scorer.score(
            "precision",
            answers("q1", 6, "q2", 1, "q3", 1, "q4", 1, "q5", 1, "q6", 1,
                "q7", 1, "q8", 1, "q9", 1, "q10", 1, "q11", 1, "q12", 1, "q13", 1)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 1 and 5");
    }

    private Map<String, Object> answers(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
