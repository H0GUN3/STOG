package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TravelGuideAiPlaceConstraintsTest {
    @Test
    void rejectsAVisitThatRunsPastCanonicalClosingTime() {
        var place = new TravelGuideAiProposalRepository.BasketPlaceDetails(
            1L,
            "FOOD",
            null,
            null,
            null,
            null,
            null,
            null,
            """
            [{"day":"MONDAY","opens_at":"09:00","closes_at":"12:00"}]
            """,
            90
        );

        assertThat(TravelGuideAiPlaceConstraints.isOpenDuring(
            place,
            LocalDate.of(2026, 8, 31),
            1,
            LocalTime.of(11, 0),
            90
        )).isFalse();
    }

    @Test
    void acceptsAnUnknownOpeningScheduleWithoutRejectingTheProposal() {
        var place = new TravelGuideAiProposalRepository.BasketPlaceDetails(
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

        assertThat(TravelGuideAiPlaceConstraints.isOpenDuring(
            place,
            LocalDate.of(2026, 8, 31),
            1,
            LocalTime.of(11, 0),
            90
        )).isTrue();
    }
}
