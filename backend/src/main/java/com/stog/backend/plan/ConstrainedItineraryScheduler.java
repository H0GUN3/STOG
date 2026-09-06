package com.stog.backend.plan;

import com.stog.backend.google.GoogleProviderException;
import com.stog.backend.trail.TrailCalculator;
import com.stog.backend.trail.TrailRequests;
import com.stog.backend.trail.TrailResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class ConstrainedItineraryScheduler {
    private ConstrainedItineraryScheduler() {
    }

    static Result generate(
        List<ItineraryFeasibility.DayWindow> dayWindows,
        List<ItineraryFeasibility.ProposedItem> requestedItems,
        List<ItineraryFeasibility.ProposedItem> fixedItems,
        List<TravelGuideAiProposalRepository.BasketCoordinate> coordinates,
        TrailRequests.TravelMode travelMode,
        TrailCalculator trails
    ) {
        return generate(
            dayWindows, requestedItems, fixedItems, coordinates, Map.of(), travelMode, trails
        );
    }

    static Result generate(
        List<ItineraryFeasibility.DayWindow> dayWindows,
        List<ItineraryFeasibility.ProposedItem> requestedItems,
        List<ItineraryFeasibility.ProposedItem> fixedItems,
        List<TravelGuideAiProposalRepository.BasketCoordinate> coordinates,
        Map<Long, Double> preferenceScores,
        TrailRequests.TravelMode travelMode,
        TrailCalculator trails
    ) {
        Map<Integer, ItineraryFeasibility.DayWindow> windows = new HashMap<>();
        dayWindows.forEach(window -> windows.put(window.dayNumber(), window));
        Map<Long, ItineraryFeasibility.ProposedItem> fixedById = new HashMap<>();
        fixedItems.forEach(item -> fixedById.put(item.basketItemId(), item));
        Map<Long, TravelGuideAiProposalRepository.BasketCoordinate> coordinatesById = new HashMap<>();
        coordinates.forEach(value -> coordinatesById.put(value.basketItemId(), value));

        List<ItineraryFeasibility.ProposedItem> resolved = requestedItems.stream()
            .filter(item -> coordinatesById.containsKey(item.basketItemId()))
            .map(item -> fixedById.getOrDefault(item.basketItemId(), item))
            .sorted(Comparator.comparingInt(ItineraryFeasibility.ProposedItem::dayNumber)
                .thenComparingInt(ItineraryFeasibility.ProposedItem::orderIndex)
                .thenComparingLong(ItineraryFeasibility.ProposedItem::basketItemId))
            .toList();
        List<ItineraryFeasibility.ProposedItem> ordered = orderByRoute(
            resolved,
            coordinatesById,
            fixedById,
            preferenceScores
        );
        List<ItineraryFeasibility.ProposedItem> generated = new ArrayList<>();
        List<TravelGuideAiResponses.TrailLeg> legs = new ArrayList<>();
        Map<Integer, LocalTime> cursorByDay = new HashMap<>();
        Map<Integer, ItineraryFeasibility.ProposedItem> previousByDay = new HashMap<>();

        for (ItineraryFeasibility.ProposedItem item : ordered) {
            ItineraryFeasibility.DayWindow window = windows.get(item.dayNumber());
            LocalTime cursor = cursorByDay.getOrDefault(
                item.dayNumber(),
                window == null ? item.plannedArrival() : window.start()
            );
            ItineraryFeasibility.ProposedItem previous = previousByDay.get(item.dayNumber());
            int travelMinutes = 0;
            if (previous != null) {
                TravelGuideAiProposalRepository.BasketCoordinate from = coordinatesById.get(previous.basketItemId());
                TravelGuideAiProposalRepository.BasketCoordinate to = coordinatesById.get(item.basketItemId());
                TrailResult route = trails.compute(new TrailRequests.Compute(
                    new TrailRequests.Coordinate(from.latitude(), from.longitude()),
                    new TrailRequests.Coordinate(to.latitude(), to.longitude()),
                    List.of(),
                    travelMode
                ));
                travelMinutes = durationMinutes(route.duration());
                legs.add(new TravelGuideAiResponses.TrailLeg(
                    previous.basketItemId(), item.basketItemId(), travelMode.name(), travelMinutes,
                    route.distance_meters(), route.encoded_polyline()
                ));
            }
            LocalTime earliest = cursor.plusMinutes(travelMinutes);
            ItineraryFeasibility.ProposedItem fixed = fixedById.get(item.basketItemId());
            LocalTime arrival = fixed == null ? earliest : fixed.plannedArrival();
            Integer duration = fixed == null ? item.plannedDurationMinutes() : fixed.plannedDurationMinutes();
            boolean isFixed = fixed != null;
            ItineraryFeasibility.ProposedItem scheduled = new ItineraryFeasibility.ProposedItem(
                item.basketItemId(), item.dayNumber(), item.orderIndex(), arrival,
                duration, travelMinutes, isFixed
            );
            generated.add(scheduled);
            previousByDay.put(item.dayNumber(), scheduled);
            cursorByDay.put(item.dayNumber(), arrival.plusMinutes(duration));
        }
        return new Result(generated, legs);
    }

    private static List<ItineraryFeasibility.ProposedItem> orderByRoute(
        List<ItineraryFeasibility.ProposedItem> items,
        Map<Long, TravelGuideAiProposalRepository.BasketCoordinate> coordinates,
        Map<Long, ItineraryFeasibility.ProposedItem> fixedById,
        Map<Long, Double> preferenceScores
    ) {
        List<ItineraryFeasibility.ProposedItem> result = new ArrayList<>();
        items.stream()
            .map(ItineraryFeasibility.ProposedItem::dayNumber)
            .distinct()
            .sorted()
            .forEach(day -> {
                List<ItineraryFeasibility.ProposedItem> dayItems = items.stream()
                    .filter(item -> item.dayNumber() == day)
                    .toList();
                if (dayItems.stream().anyMatch(item -> fixedById.containsKey(item.basketItemId()))) {
                    result.addAll(dayItems);
                } else {
                    result.addAll(nearestNeighbour(dayItems, coordinates, preferenceScores));
                }
            });
        return result;
    }

    private static List<ItineraryFeasibility.ProposedItem> nearestNeighbour(
        List<ItineraryFeasibility.ProposedItem> items,
        Map<Long, TravelGuideAiProposalRepository.BasketCoordinate> coordinates,
        Map<Long, Double> preferenceScores
    ) {
        if (items.size() < 3) {
            return items;
        }
        List<ItineraryFeasibility.ProposedItem> remaining = new ArrayList<>(items);
        List<ItineraryFeasibility.ProposedItem> ordered = new ArrayList<>();
        ordered.add(remaining.stream()
            .max(Comparator.comparingDouble(item ->
                preferenceScores.getOrDefault(item.basketItemId(), 0.0)
            ))
            .orElseThrow());
        remaining.remove(ordered.get(0));
        while (!remaining.isEmpty()) {
            ItineraryFeasibility.ProposedItem previous = ordered.get(ordered.size() - 1);
            TravelGuideAiProposalRepository.BasketCoordinate from =
                coordinates.get(previous.basketItemId());
            ItineraryFeasibility.ProposedItem next = remaining.stream()
                .max(Comparator
                    .comparingDouble((ItineraryFeasibility.ProposedItem item) ->
                        preferenceScores.getOrDefault(item.basketItemId(), 0.0)
                    )
                    .thenComparing(
                        item -> distanceMeters(from, coordinates.get(item.basketItemId())),
                        Comparator.reverseOrder()
                    )
                )
                .orElseThrow();
            ordered.add(next);
            remaining.remove(next);
        }
        return ordered;
    }

    private static double distanceMeters(
        TravelGuideAiProposalRepository.BasketCoordinate from,
        TravelGuideAiProposalRepository.BasketCoordinate to
    ) {
        double latitudeDelta = Math.toRadians(to.latitude() - from.latitude());
        double longitudeDelta = Math.toRadians(to.longitude() - from.longitude());
        double averageLatitude = Math.toRadians((from.latitude() + to.latitude()) / 2.0);
        double x = longitudeDelta * Math.cos(averageLatitude);
        double y = latitudeDelta;
        return Math.sqrt(x * x + y * y) * 6_371_000.0;
    }

    private static int durationMinutes(String duration) {
        if (duration == null || !duration.matches("[0-9]+(?:\\.[0-9]+)?s")) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                "GOOGLE_ROUTES_EMPTY",
                true
            );
        }
        BigDecimal seconds = new BigDecimal(duration.substring(0, duration.length() - 1));
        return seconds.divide(BigDecimal.valueOf(60), 0, RoundingMode.CEILING).intValueExact();
    }

    record Result(
        List<ItineraryFeasibility.ProposedItem> items,
        List<TravelGuideAiResponses.TrailLeg> trailLegs
    ) {
        Result {
            items = List.copyOf(items);
            trailLegs = List.copyOf(trailLegs);
        }
    }
}
