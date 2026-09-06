package com.stog.backend.place.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceSearchNormalizerTest {
    private final PlaceSearchNormalizer normalizer = new PlaceSearchNormalizer();

    @Test
    void normalizesNfkcCaseAndSeparatorsBeforeDerivingCompactKey() {
        PlaceSearchNormalizer.Normalized normalized = normalizer.normalize(
            "  전주·HANOK＿마을!!  "
        );

        assertThat(normalized.normalized_name()).isEqualTo("전주 hanok 마을");
        assertThat(normalized.compact_name()).isEqualTo("전주hanok마을");
    }

    @Test
    void punctuationOnlyInputNormalizesToAnEmptyKey() {
        PlaceSearchNormalizer.Normalized normalized = normalizer.normalize(" -- !! ");

        assertThat(normalized.normalized_name()).isEmpty();
        assertThat(normalized.compact_name()).isEmpty();
    }
}
