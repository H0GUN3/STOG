package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.stog.backend.trail.TrailRequests;
import com.stog.backend.trail.TrailResult;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstrainedItinerarySchedulerTest {
    @Test
    void generatesArrivalAndTrailFromInjectedRouteDurationWhilePreservingFixedItem() {
        var requested = List.of(
            item(11, 0, "09:00", false),
            item(12, 1, "09:05", false)
        );
        var fixed = List.of(item(11, 0, "09:00", true));

        var result = ConstrainedItineraryScheduler.generate(
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            requested,
            fixed,
            List.of(
                new TravelGuideAiProposalRepository.BasketCoordinate(11, 35.8, 127.1),
                new TravelGuideAiProposalRepository.BasketCoordinate(12, 35.9, 127.2)
            ),
            TrailRequests.TravelMode.WALK,
            request -> new TrailResult("601s", 1200L, "polyline")
        );

        assertThat(result.items()).containsExactly(
            item(11, 0, "09:00", true),
            new ItineraryFeasibility.ProposedItem(12, 1, 1, LocalTime.of(10, 11), 30, 11, false)
        );
        assertThat(result.trailLegs()).containsExactly(
            new TravelGuideAiResponses.TrailLeg(11, 12, "WALK", 11, 1200L, "polyline")
        );
    }

    @Test
    void excludesUnresolvedItemBeforeCallingRouteSeam() {
        var result = ConstrainedItineraryScheduler.generate(
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            List.of(item(11, 0, "09:00", false), item(99, 1, "10:00", false)),
            List.of(),
            List.of(new TravelGuideAiProposalRepository.BasketCoordinate(11, 35.8, 127.1)),
            TrailRequests.TravelMode.WALK,
            request -> { throw new AssertionError("No route is needed for one resolved item"); }
        );

        assertThat(result.items()).extracting(ItineraryFeasibility.ProposedItem::basketItemId)
            .containsExactly(11L);
        assertThat(result.trailLegs()).isEmpty();
    }

    @Test
    void prefersHigherPreferenceBeforeUsingDistanceAsTieBreaker() {
        var requested = List.of(
            item(11, 0, "09:00", false),
            item(12, 1, "10:00", false),
            item(13, 2, "11:00", false)
        );

        var result = ConstrainedItineraryScheduler.generate(
            List.of(new ItineraryFeasibility.DayWindow(1, LocalTime.of(9, 0), LocalTime.of(18, 0))),
            requested,
            List.of(),
            List.of(
                new TravelGuideAiProposalRepository.BasketCoordinate(11, 35.0000, 127.0000),
                new TravelGuideAiProposalRepository.BasketCoordinate(12, 35.0001, 127.0001),
                new TravelGuideAiProposalRepository.BasketCoordinate(13, 36.0000, 128.0000)
            ),
            Map.of(11L, 0.0, 12L, 0.1, 13L, 1.0),
            TrailRequests.TravelMode.WALK,
            request -> new TrailResult("60s", 1L, "polyline")
        );

        assertThat(result.items())
            .extracting(ItineraryFeasibility.ProposedItem::basketItemId)
            .containsExactly(13L, 12L, 11L);
    }

    private static ItineraryFeasibility.ProposedItem item(
        long id,
        int order,
        String arrival,
        boolean fixed
    ) {
        return new ItineraryFeasibility.ProposedItem(
            id, 1, order, LocalTime.parse(arrival), order == 0 ? 60 : 30, 0, fixed
        );
    }
}
