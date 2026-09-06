package com.stog.backend.plan;

import java.util.Locale;
import java.util.Map;

final class TravelGuideAiPlaceScorer {
    private TravelGuideAiPlaceScorer() {
    }

    static double similarity(
        TravelGuideAiProposalRepository.BasketPlaceDetails place,
        Map<String, Double> preferences
    ) {
        if (place == null) {
            return 0.0;
        }
        if (!hasTraits(place)) {
            return categoryScore(place.category(), preferences);
        }

        double[] user = {
            preference(preferences, "nature"),
            preference(preferences, "culture"),
            preference(preferences, "food"),
            preference(preferences, "shopping"),
            preference(preferences, "experience"),
            preference(preferences, "relaxation")
        };
        double[] traits = {
            value(place.natureTrait()),
            value(place.cultureTrait()),
            value(place.foodTrait()),
            value(place.shoppingTrait()),
            value(place.experienceTrait()),
            value(place.relaxationTrait())
        };
        double dot = 0.0;
        double userNorm = 0.0;
        double traitNorm = 0.0;
        for (int index = 0; index < user.length; index++) {
            dot += user[index] * traits[index];
            userNorm += user[index] * user[index];
            traitNorm += traits[index] * traits[index];
        }
        if (userNorm == 0.0 || traitNorm == 0.0) {
            return categoryScore(place.category(), preferences);
        }
        return dot / Math.sqrt(userNorm * traitNorm);
    }

    private static boolean hasTraits(TravelGuideAiProposalRepository.BasketPlaceDetails place) {
        return place.natureTrait() != null
            || place.cultureTrait() != null
            || place.foodTrait() != null
            || place.shoppingTrait() != null
            || place.experienceTrait() != null
            || place.relaxationTrait() != null;
    }

    private static double value(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double preference(Map<String, Double> preferences, String axis) {
        return preferences.getOrDefault(axis, 0.0);
    }

    private static double categoryScore(String category, Map<String, Double> preferences) {
        String value = category == null ? "" : category.toLowerCase(Locale.ROOT);
        String axis;
        if (value.contains("nature")
            || value.contains("park")
            || value.contains("beach")
            || value.contains("mountain")) {
            axis = "nature";
        } else if (value.contains("museum")
            || value.contains("gallery")
            || value.contains("historic")
            || value.contains("culture")
            || value.contains("temple")
            || value.contains("palace")) {
            axis = "culture";
        } else if (value.contains("food")
            || value.contains("restaurant")
            || value.contains("cafe")
            || value.contains("bakery")
            || value.contains("bar")) {
            axis = "food";
        } else if (value.contains("shopping")
            || value.contains("shop")
            || value.contains("mall")) {
            axis = "shopping";
        } else if (value.contains("spa")
            || value.contains("relax")
            || value.contains("resort")) {
            axis = "relaxation";
        } else {
            axis = "experience";
        }
        return preference(preferences, axis);
    }
}
