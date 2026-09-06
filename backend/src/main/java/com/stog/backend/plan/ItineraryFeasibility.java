package com.stog.backend.plan;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItineraryFeasibility {
    private ItineraryFeasibility() {
    }

    public static Output evaluate(Input input) {
        List<ProposedItem> included = input.proposedItems().stream()
            .filter(item -> input.resolvedBasketItemIds().contains(item.basketItemId()))
            .sorted(ITEM_ORDER)
            .toList();
        List<Long> excluded = input.proposedItems().stream()
            .filter(item -> !input.resolvedBasketItemIds().contains(item.basketItemId()))
            .sorted(ITEM_ORDER)
            .map(ProposedItem::basketItemId)
            .distinct()
            .toList();
        List<Violation> violations = new ArrayList<>();
        Map<Long, ProposedItem> proposedById = new HashMap<>();
        for (ProposedItem item : included) {
            proposedById.putIfAbsent(item.basketItemId(), item);
        }
        input.currentFixedItems().stream()
            .sorted(Comparator.comparingLong(ProposedItem::basketItemId))
            .filter(current -> !samePlacement(current, proposedById.get(current.basketItemId())))
            .map(current -> new Violation("fixed_item_changed", current.basketItemId()))
            .forEach(violations::add);

        Map<Integer, DayWindow> windows = new HashMap<>();
        input.dayWindows().forEach(window -> windows.put(window.dayNumber(), window));
        Set<Long> basketIds = new HashSet<>();
        Set<String> positions = new HashSet<>();
        Map<Integer, Long> previousEnds = new HashMap<>();
        for (ProposedItem item : included) {
            if (!basketIds.add(item.basketItemId())) {
                violations.add(new Violation("duplicate_basket_item", item.basketItemId()));
                continue;
            }
            if (!positions.add(item.dayNumber() + ":" + item.orderIndex())) {
                violations.add(new Violation("duplicate_position", item.basketItemId()));
            }
            if (item.dayNumber() > input.dayCount()) {
                violations.add(new Violation("day_outside_trip", item.basketItemId()));
                continue;
            }
            DayWindow window = windows.get(item.dayNumber());
            if (window == null) {
                violations.add(new Violation("day_window_missing", item.basketItemId()));
                continue;
            }
            if (item.plannedArrival() == null || item.plannedDurationMinutes() == null) {
                violations.add(new Violation("schedule_missing", item.basketItemId()));
                continue;
            }
            long arrivalMinute = item.plannedArrival().toSecondOfDay() / 60L;
            Long priorEnd = previousEnds.get(item.dayNumber());
            if (priorEnd != null && arrivalMinute < priorEnd + item.travelMinutesFromPrevious()) {
                violations.add(new Violation("travel_overlap", item.basketItemId()));
            }
            long itemEndMinute = arrivalMinute + item.plannedDurationMinutes();
            long windowStartMinute = window.start().toSecondOfDay() / 60L;
            long windowEndMinute = window.end().toSecondOfDay() / 60L;
            if (arrivalMinute < windowStartMinute || itemEndMinute > windowEndMinute) {
                violations.add(new Violation("day_window_exceeded", item.basketItemId()));
            }
            previousEnds.put(item.dayNumber(), itemEndMinute);
        }
        return new Output(
            input.tripId(),
            input.baseVersion(),
            violations.isEmpty(),
            included.stream().map(ProposedItem::basketItemId).toList(),
            excluded,
            violations
        );
    }

    private static boolean samePlacement(ProposedItem current, ProposedItem proposed) {
        return proposed != null
            && proposed.fixed()
            && current.dayNumber() == proposed.dayNumber()
            && current.orderIndex() == proposed.orderIndex()
            && java.util.Objects.equals(current.plannedArrival(), proposed.plannedArrival())
            && java.util.Objects.equals(current.plannedDurationMinutes(), proposed.plannedDurationMinutes());
    }

    private static final Comparator<ProposedItem> ITEM_ORDER = Comparator
        .comparingInt(ProposedItem::dayNumber)
        .thenComparingInt(ProposedItem::orderIndex)
        .thenComparingLong(ProposedItem::basketItemId);

    public record Input(
        long tripId,
        long baseVersion,
        int dayCount,
        List<DayWindow> dayWindows,
        List<ProposedItem> currentFixedItems,
        List<ProposedItem> proposedItems,
        Set<Long> resolvedBasketItemIds
    ) {
        public Input {
            if (tripId < 1 || baseVersion < 0 || dayCount < 1) {
                throw new IllegalArgumentException("Feasibility scope is invalid");
            }
            dayWindows = List.copyOf(dayWindows);
            currentFixedItems = List.copyOf(currentFixedItems);
            proposedItems = List.copyOf(proposedItems);
            resolvedBasketItemIds = Set.copyOf(resolvedBasketItemIds);
        }
    }

    public record DayWindow(int dayNumber, LocalTime start, LocalTime end) {
        public DayWindow {
            if (dayNumber < 1 || !end.isAfter(start)) {
                throw new IllegalArgumentException("Day window is invalid");
            }
        }
    }

    public record ProposedItem(
        long basketItemId,
        int dayNumber,
        int orderIndex,
        LocalTime plannedArrival,
        Integer plannedDurationMinutes,
        int travelMinutesFromPrevious,
        boolean fixed
    ) {
        public ProposedItem {
            if (basketItemId < 1 || dayNumber < 1 || orderIndex < 0
                || (plannedDurationMinutes != null && plannedDurationMinutes < 1)
                || travelMinutesFromPrevious < 0) {
                throw new IllegalArgumentException("Proposed itinerary item is invalid");
            }
        }
    }

    public record Violation(String code, Long basketItemId) {
    }

    public record Output(
        long tripId,
        long baseVersion,
        boolean feasible,
        List<Long> includedBasketItemIds,
        List<Long> excludedUnresolvedBasketItemIds,
        List<Violation> violations
    ) {
        public Output {
            includedBasketItemIds = List.copyOf(includedBasketItemIds);
            excludedUnresolvedBasketItemIds = List.copyOf(excludedUnresolvedBasketItemIds);
            violations = List.copyOf(violations);
        }
    }
}
