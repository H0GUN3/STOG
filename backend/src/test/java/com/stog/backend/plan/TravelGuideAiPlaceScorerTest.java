package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelGuideAiPlaceScorerTest {
    @Test
    void prefersCanonicalTraitsThatMatchUserPreferenceVector() {
        var nature = new TravelGuideAiProposalRepository.BasketPlaceDetails(
            1L,
            "ATTRACTION",
            1.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            null,
            null
        );
        var food = new TravelGuideAiProposalRepository.BasketPlaceDetails(
            2L,
            "FOOD",
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            0.0,
            null,
            null
        );
        Map<String, Double> preferences = Map.of(
            "nature", 1.0,
            "culture", 0.0,
            "food", 0.0,
            "shopping", 0.0,
            "experience", 0.0,
            "relaxation", 0.0
        );

        assertThat(TravelGuideAiPlaceScorer.similarity(nature, preferences))
            .isGreaterThan(TravelGuideAiPlaceScorer.similarity(food, preferences));
    }

    @Test
    void fallsBackToCategoryWhenCanonicalTraitsAreUnavailable() {
        var food = new TravelGuideAiProposalRepository.BasketPlaceDetails(
            1L,
            "FOOD",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        assertThat(TravelGuideAiPlaceScorer.similarity(
            food,
            Map.of(
                "nature", 0.0,
                "culture", 0.0,
                "food", 1.0,
                "shopping", 0.0,
                "experience", 0.0,
                "relaxation", 0.0
            )
        )).isEqualTo(1.0);
    }
}
