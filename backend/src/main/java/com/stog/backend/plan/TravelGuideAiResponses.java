package com.stog.backend.plan;

import java.util.List;
import java.util.UUID;

public final class TravelGuideAiResponses {
    private TravelGuideAiResponses() {
    }

    public record Preview(
        UUID suggestion_id,
        long trip_id,
        long base_version,
        String status,
        String proposal_fingerprint,
        boolean feasible,
        List<Action> actions,
        List<Long> fixed_basket_item_ids,
        List<Long> excluded_unresolved_basket_item_ids,
        List<Violation> violations,
        List<TrailLeg> trail_legs
    ) {
        public Preview {
            actions = List.copyOf(actions);
            fixed_basket_item_ids = List.copyOf(fixed_basket_item_ids);
            excluded_unresolved_basket_item_ids = List.copyOf(excluded_unresolved_basket_item_ids);
            violations = List.copyOf(violations);
            trail_legs = List.copyOf(trail_legs);
        }

        public Preview(
            UUID suggestionId,
            long tripId,
            long baseVersion,
            String status,
            String proposalFingerprint,
            boolean feasible,
            List<Action> actions,
            List<Long> fixedBasketItemIds,
            List<Long> excludedUnresolvedBasketItemIds,
            List<Violation> violations
        ) {
            this(
                suggestionId, tripId, baseVersion, status, proposalFingerprint, feasible,
                actions, fixedBasketItemIds, excludedUnresolvedBasketItemIds, violations, List.of()
            );
        }
    }

    public record Action(
        int action_order,
        String type,
        long basket_item_id,
        int day_number,
        int order_index,
        String planned_arrival,
        int planned_duration_min,
        int travel_minutes_from_previous,
        boolean is_fixed
    ) {
    }

    public record Violation(String code, Long basket_item_id) {
    }

    public record TrailLeg(
        long from_basket_item_id,
        long to_basket_item_id,
        String travel_mode,
        int duration_minutes,
        Long distance_meters,
        String encoded_polyline
    ) {
    }

    public record Applied(
        UUID suggestion_id,
        long trip_id,
        String status,
        long itinerary_version,
        long itinerary_change_id,
        List<ItineraryResponses.Item> items
    ) {
        public Applied {
            items = List.copyOf(items);
        }
    }
}
