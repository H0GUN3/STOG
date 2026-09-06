package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItineraryFeasibilityTest {
    @Test
    void reportsFeasibleScheduleWhenResolvedItemsFitDayWindow() {
        // Given
        ItineraryFeasibility.Input input = new ItineraryFeasibility.Input(
            14,
            3,
            1,
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            List.of(),
            List.of(
                item(11, 1, 0, "09:00", 60, 0, false),
                item(12, 1, 1, "10:30", 45, 30, false)
            ),
            Set.of(11L, 12L)
        );

        // When
        ItineraryFeasibility.Output output = ItineraryFeasibility.evaluate(input);

        // Then
        assertThat(output.tripId()).isEqualTo(14);
        assertThat(output.baseVersion()).isEqualTo(3);
        assertThat(output.feasible()).isTrue();
        assertThat(output.includedBasketItemIds()).containsExactly(11L, 12L);
        assertThat(output.excludedUnresolvedBasketItemIds()).isEmpty();
        assertThat(output.violations()).isEmpty();
    }

    @Test
    void excludesUnresolvedItemsBeforeTravelCalculationAndOrdersViolations() {
        // Given
        ItineraryFeasibility.Input input = new ItineraryFeasibility.Input(
            14,
            3,
            1,
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(12, 0))),
            List.of(),
            List.of(
                item(13, 1, 0, "09:00", 300, 0, false),
                item(99, 1, 1, "09:01", 10, 999, false)
            ),
            Set.of(13L)
        );

        // When
        ItineraryFeasibility.Output output = ItineraryFeasibility.evaluate(input);

        // Then
        assertThat(output.feasible()).isFalse();
        assertThat(output.includedBasketItemIds()).containsExactly(13L);
        assertThat(output.excludedUnresolvedBasketItemIds()).containsExactly(99L);
        assertThat(output.violations())
            .extracting(ItineraryFeasibility.Violation::code)
            .containsExactly("day_window_exceeded");
    }

    @Test
    void visitDurationCannotWrapPastTheDayWindow() {
        // Given
        ItineraryFeasibility.Input input = new ItineraryFeasibility.Input(
            14,
            3,
            1,
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(20, 0), LocalTime.of(23, 59))),
            List.of(),
            List.of(item(20, 1, 0, "23:30", 60, 0, false)),
            Set.of(20L)
        );

        // When
        ItineraryFeasibility.Output output = ItineraryFeasibility.evaluate(input);

        // Then
        assertThat(output.violations())
            .containsExactly(new ItineraryFeasibility.Violation("day_window_exceeded", 20L));
    }

    @Test
    void reportsFixedItemChangesWithoutChangingCurrentItem() {
        // Given
        ItineraryFeasibility.ProposedItem fixed = item(21, 1, 0, "10:00", 60, 0, true);
        ItineraryFeasibility.Input input = new ItineraryFeasibility.Input(
            14,
            3,
            1,
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            List.of(fixed),
            List.of(item(21, 1, 1, "11:00", 60, 0, true)),
            Set.of(21L)
        );

        // When
        ItineraryFeasibility.Output output = ItineraryFeasibility.evaluate(input);

        // Then
        assertThat(output.feasible()).isFalse();
        assertThat(output.violations())
            .containsExactly(new ItineraryFeasibility.Violation("fixed_item_changed", 21L));
        assertThat(fixed).isEqualTo(item(21, 1, 0, "10:00", 60, 0, true));
    }

    private static ItineraryFeasibility.ProposedItem item(
        long basketItemId,
        int dayNumber,
        int orderIndex,
        String arrival,
        int durationMinutes,
        int travelMinutes,
        boolean fixed
    ) {
        return new ItineraryFeasibility.ProposedItem(
            basketItemId,
            dayNumber,
            orderIndex,
            LocalTime.parse(arrival),
            durationMinutes,
            travelMinutes,
            fixed
        );
    }
}
