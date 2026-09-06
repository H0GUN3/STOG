package com.stog.backend.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SurveyProfileScorer {
    public static final String VERSION = "v1";

    private static final List<String> PREFERENCE_KEYS = List.of(
        "nature", "culture", "food", "shopping", "experience", "relaxation"
    );
    private static final List<String> TRAVEL_STYLE_KEYS = List.of(
        "localness",
        "crowd_tolerance",
        "pace",
        "spontaneity",
        "activity_intensity",
        "novelty_seeking",
        "travel_effort_tolerance"
    );

    public Result score(String surveyType, Map<String, Object> answers) {
        return switch (surveyType) {
            case "cold_start" -> coldStart(answers);
            case "precision" -> precision(answers);
            default -> throw new IllegalArgumentException(
                "survey_type must be cold_start or precision"
            );
        };
    }

    private Result coldStart(Map<String, Object> answers) {
        requireKeys(answers, 5);
        Map<String, Double> preference = neutral(PREFERENCE_KEYS);
        String preferenceKey = text(answers, "q1", List.of(
            "nature", "culture", "shopping", "food", "experience", "relaxation"
        ));
        preference.put(preferenceKey, 0.75);

        Map<String, Double> travelStyle = neutral(TRAVEL_STYLE_KEYS);
        travelStyle.put("localness", choice(
            text(answers, "q2", List.of("representative", "local")),
            "representative", 0.25, "local", 0.75
        ));
        travelStyle.put("pace", choice(
            text(answers, "q3", List.of("leisurely", "packed")),
            "leisurely", 0.25, "packed", 0.75
        ));
        travelStyle.put("spontaneity", choice(
            text(answers, "q4", List.of("planned", "spontaneous")),
            "planned", 0.25, "spontaneous", 0.75
        ));
        travelStyle.put("activity_intensity", choice(
            text(answers, "q5", List.of("comfortable", "active")),
            "comfortable", 0.25, "active", 0.75
        ));
        return new Result(preference, travelStyle, false);
    }

    private Result precision(Map<String, Object> answers) {
        requireKeys(answers, 13);
        Map<String, Double> preference = new LinkedHashMap<>();
        preference.put("nature", normalize(answer(answers, "q1")));
        preference.put("culture", normalize(answer(answers, "q2")));
        preference.put("food", normalize(answer(answers, "q3")));
        preference.put("shopping", normalize(answer(answers, "q4")));
        preference.put("experience", normalize(answer(answers, "q5")));
        preference.put("relaxation", normalize(answer(answers, "q6")));

        Map<String, Double> travelStyle = new LinkedHashMap<>();
        travelStyle.put("localness", reverseNormalize(answer(answers, "q7")));
        travelStyle.put("crowd_tolerance", normalize(answer(answers, "q8")));
        travelStyle.put("pace", normalize(answer(answers, "q9")));
        travelStyle.put("spontaneity", reverseNormalize(answer(answers, "q10")));
        travelStyle.put("activity_intensity", normalize(answer(answers, "q11")));
        travelStyle.put("novelty_seeking", normalize(answer(answers, "q12")));
        travelStyle.put(
            "travel_effort_tolerance",
            normalize(answer(answers, "q13"))
        );
        return new Result(preference, travelStyle, true);
    }

    private static Map<String, Double> neutral(List<String> keys) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, 0.5);
        }
        return values;
    }

    private static void requireKeys(Map<String, Object> answers, int count) {
        if (answers == null || answers.size() != count) {
            throw new IllegalArgumentException(
                "answers must contain exactly q1 through q" + count
            );
        }
        for (int index = 1; index <= count; index++) {
            if (!answers.containsKey("q" + index)) {
                throw new IllegalArgumentException("answers is missing q" + index);
            }
        }
    }

    private static int answer(Map<String, Object> answers, String key) {
        Object value = answers.get(key);
        if (!(value instanceof Number number)
            || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(key + " must be an integer between 1 and 5");
        }
        int answer = ((Number) value).intValue();
        if (answer < 1 || answer > 5) {
            throw new IllegalArgumentException(key + " must be between 1 and 5");
        }
        return answer;
    }

    private static String text(
        Map<String, Object> answers,
        String key,
        List<String> allowed
    ) {
        Object value = answers.get(key);
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw new IllegalArgumentException(key + " contains an unsupported answer");
        }
        return text;
    }

    private static double choice(
        String value,
        String first,
        double firstScore,
        String second,
        double secondScore
    ) {
        return value.equals(first) ? firstScore : secondScore;
    }

    private static double normalize(int answer) {
        return (answer - 1) / 4.0;
    }

    private static double reverseNormalize(int answer) {
        return (5 - answer) / 4.0;
    }

    public record Result(
        Map<String, Double> preference,
        Map<String, Double> travelStyle,
        boolean canonical
    ) {
        public Result {
            preference = Map.copyOf(preference);
            travelStyle = Map.copyOf(travelStyle);
        }
    }
}
