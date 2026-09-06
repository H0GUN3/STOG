package com.stog.backend.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.stog.backend.trail.TrailCalculator;
import com.stog.backend.profile.ProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TravelGuideAiPreviewService {
    private final TripMembershipPolicy memberships;
    private final ItineraryRepository itineraries;
    private final TravelGuideAiProposalRepository proposals;
    private final TrailCalculator trails;
    private final ProfileService profiles;

    public TravelGuideAiPreviewService(
        TripMembershipPolicy memberships,
        ItineraryRepository itineraries,
        TravelGuideAiProposalRepository proposals,
        TrailCalculator trails,
        ProfileService profiles
    ) {
        this.memberships = memberships;
        this.itineraries = itineraries;
        this.proposals = proposals;
        this.trails = trails;
        this.profiles = profiles;
    }

    @Transactional
    public TravelGuideAiResponses.Preview preview(
        long userId,
        long tripId,
        TravelGuideAiRequests.Preview request
    ) {
        memberships.requireActiveMember(userId, tripId);
        long version = itineraries.currentVersion(tripId);
        if (version != request.base_version()) {
            throw conflict("PROPOSAL_STALE", "Itinerary version changed before preview");
        }
        List<ItineraryFeasibility.DayWindow> windows = parseWindows(request.day_windows());
        List<ItineraryFeasibility.ProposedItem> parsedItems = request.actions().stream()
            .map(TravelGuideAiPreviewService::toProposedItem)
            .toList();
        List<Long> requestedIds = parsedItems.stream()
            .map(ItineraryFeasibility.ProposedItem::basketItemId)
            .toList();
        List<ItineraryFeasibility.ProposedItem> fixedItems = itineraries.findFixedByTrip(tripId);
        List<Long> detailIds = new ArrayList<>(requestedIds);
        fixedItems.stream()
            .map(ItineraryFeasibility.ProposedItem::basketItemId)
            .filter(id -> !detailIds.contains(id))
            .forEach(detailIds::add);
        Map<Long, TravelGuideAiProposalRepository.BasketPlaceDetails> placeDetails =
            proposals.basketPlaceDetails(tripId, detailIds).stream()
                .collect(Collectors.toMap(
                    TravelGuideAiProposalRepository.BasketPlaceDetails::basketItemId,
                    Function.identity(),
                    (left, right) -> left
                ));
        Map<String, Double> preferences = profiles.get(userId).preference_scores();
        List<ItineraryFeasibility.ProposedItem> requestedItems = preferenceOrder(
            parsedItems, placeDetails, preferences
        );
        Map<Long, Double> preferenceScores = placeDetails.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> TravelGuideAiPlaceScorer.similarity(
                    entry.getValue(), preferences
                )
            ));
        Set<Long> resolvedIds = proposals.resolvedBasketItemIds(tripId, requestedIds);
        ConstrainedItineraryScheduler.Result generated = request.travel_mode() == null
            ? new ConstrainedItineraryScheduler.Result(
                requestedItems.stream().filter(item -> resolvedIds.contains(item.basketItemId())).toList(),
                List.of()
            )
            : ConstrainedItineraryScheduler.generate(
                windows,
                requestedItems,
                fixedItems,
                proposals.basketCoordinates(tripId, requestedIds),
                preferenceScores,
                request.travel_mode(),
                trails
            );
        Map<Long, ItineraryFeasibility.ProposedItem> generatedById = generated.items().stream()
            .collect(Collectors.toMap(ItineraryFeasibility.ProposedItem::basketItemId, Function.identity()));
        List<ItineraryFeasibility.ProposedItem> evaluatedItems = requestedItems.stream()
            .map(item -> generatedById.getOrDefault(item.basketItemId(), item))
            .toList();
        ItineraryFeasibility.Output feasibility = ItineraryFeasibility.evaluate(
            new ItineraryFeasibility.Input(
                tripId, version, itineraries.dayCount(tripId), windows,
                fixedItems, evaluatedItems, resolvedIds
            )
        );
        LocalDate tripStartDate = proposals.tripStartDate(tripId).orElse(null);
        List<TravelGuideAiResponses.Violation> openingViolations = generated.items().stream()
            .filter(item -> {
                TravelGuideAiProposalRepository.BasketPlaceDetails place =
                    placeDetails.get(item.basketItemId());
                return place != null && !TravelGuideAiPlaceConstraints.isOpenDuring(
                    place,
                    tripStartDate,
                    item.dayNumber(),
                    item.plannedArrival(),
                    item.plannedDurationMinutes()
                );
            })
            .map(item -> new TravelGuideAiResponses.Violation(
                "PLACE_CLOSED", item.basketItemId()
            ))
            .toList();
        List<TravelGuideAiResponses.Action> sortedActions = generated.items().stream()
            .sorted(Comparator.comparingInt(ItineraryFeasibility.ProposedItem::dayNumber)
                .thenComparingInt(ItineraryFeasibility.ProposedItem::orderIndex)
                .thenComparingLong(ItineraryFeasibility.ProposedItem::basketItemId))
            .map(item -> toResponseAction(item, 0))
            .toList();
        List<TravelGuideAiResponses.Action> actions = java.util.stream.IntStream.range(0, sortedActions.size())
            .mapToObj(index -> withOrder(sortedActions.get(index), index))
            .toList();
        List<TravelGuideAiResponses.Violation> violations = new ArrayList<>(
            feasibility.violations().stream()
            .map(item -> new TravelGuideAiResponses.Violation(item.code(), item.basketItemId()))
            .toList()
        );
        violations.addAll(openingViolations);
        boolean feasible = feasibility.feasible() && openingViolations.isEmpty();
        List<Long> fixedIds = actions.stream()
            .filter(TravelGuideAiResponses.Action::is_fixed)
            .map(TravelGuideAiResponses.Action::basket_item_id)
            .toList();
        UUID suggestionId = UUID.randomUUID();
        String fingerprint = fingerprint(new FingerprintPayload(
            tripId, version, actions, feasibility.excludedUnresolvedBasketItemIds(), violations,
            generated.trailLegs()
        ));
        TravelGuideAiProposalRepository.Draft draft = new TravelGuideAiProposalRepository.Draft(
            suggestionId, tripId, userId, version, fingerprint, feasible, actions,
            feasibility.excludedUnresolvedBasketItemIds(), violations
        );
        proposals.save(draft);
        return new TravelGuideAiResponses.Preview(
            suggestionId, tripId, version, feasible ? "ready" : "invalid",
            fingerprint, feasible, actions, fixedIds,
            feasibility.excludedUnresolvedBasketItemIds(), violations, generated.trailLegs()
        );
    }

    private static List<ItineraryFeasibility.DayWindow> parseWindows(
        List<TravelGuideAiRequests.DayWindow> values
    ) {
        Set<Integer> days = new HashSet<>();
        List<ItineraryFeasibility.DayWindow> windows = new ArrayList<>();
        try {
            for (TravelGuideAiRequests.DayWindow value : values) {
                if (!days.add(value.day_number())) {
                    throw badRequest("PROPOSAL_DAY_WINDOW_DUPLICATE", "Day windows must be unique");
                }
                windows.add(new ItineraryFeasibility.DayWindow(
                    value.day_number(), parseMinute(value.start()), parseMinute(value.end())
                ));
            }
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw badRequest("PROPOSAL_TIME_INVALID", "Proposal time is invalid");
        }
        return List.copyOf(windows);
    }

    private static ItineraryFeasibility.ProposedItem toProposedItem(
        TravelGuideAiRequests.Action action
    ) {
        try {
            return new ItineraryFeasibility.ProposedItem(
                action.basket_item_id(), action.day_number(), action.order_index(),
                parseMinute(action.planned_arrival()), action.planned_duration_min(),
                action.travel_minutes_from_previous(), action.is_fixed()
            );
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw badRequest("PROPOSAL_ACTION_INVALID", "Proposal action is invalid");
        }
    }

    private static LocalTime parseMinute(String value) {
        LocalTime time = LocalTime.parse(value);
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("Proposal times use minute precision");
        }
        return time;
    }

    private static TravelGuideAiResponses.Action toResponseAction(
        ItineraryFeasibility.ProposedItem item,
        int order
    ) {
        return new TravelGuideAiResponses.Action(
            order, "set_itinerary_item", item.basketItemId(), item.dayNumber(), item.orderIndex(),
            item.plannedArrival().toString(), item.plannedDurationMinutes(),
            item.travelMinutesFromPrevious(), item.fixed()
        );
    }

    private static List<ItineraryFeasibility.ProposedItem> preferenceOrder(
        List<ItineraryFeasibility.ProposedItem> actions,
        Map<Long, TravelGuideAiProposalRepository.BasketPlaceDetails> placeDetails,
        Map<String, Double> preferences
    ) {
        Map<Integer, List<ItineraryFeasibility.ProposedItem>> byDay = actions.stream()
            .collect(Collectors.groupingBy(
                ItineraryFeasibility.ProposedItem::dayNumber
            ));
        List<ItineraryFeasibility.ProposedItem> ordered = new ArrayList<>();
        byDay.keySet().stream().sorted().forEach(day -> {
            List<ItineraryFeasibility.ProposedItem> dayItems = byDay.get(day).stream()
                .sorted(Comparator.comparingInt(
                    ItineraryFeasibility.ProposedItem::orderIndex
                ))
                .toList();
            List<ItineraryFeasibility.ProposedItem> flexible = dayItems.stream()
                .filter(item -> !item.fixed())
                .sorted(Comparator.comparingDouble(
                    item -> -TravelGuideAiPlaceScorer.similarity(
                        placeDetails.get(item.basketItemId()), preferences
                    )
                ))
                .toList();
            int flexibleIndex = 0;
            for (ItineraryFeasibility.ProposedItem item : dayItems) {
                ordered.add(item.fixed() ? item : flexible.get(flexibleIndex++));
            }
        });
        return ordered;
    }

    private static TravelGuideAiResponses.Action withOrder(
        TravelGuideAiResponses.Action action,
        int order
    ) {
        return new TravelGuideAiResponses.Action(
            order, action.type(), action.basket_item_id(), action.day_number(), action.order_index(),
            action.planned_arrival(), action.planned_duration_min(),
            action.travel_minutes_from_previous(), action.is_fixed()
        );
    }

    private static String fingerprint(FingerprintPayload payload) {
        List<Object> values = new ArrayList<>();
        values.add(payload.tripId());
        values.add(payload.version());
        payload.actions().forEach(action -> {
            values.add(action.action_order());
            values.add(action.type());
            values.add(action.basket_item_id());
            values.add(action.day_number());
            values.add(action.order_index());
            values.add(action.planned_arrival());
            values.add(action.planned_duration_min());
            values.add(action.travel_minutes_from_previous());
            values.add(action.is_fixed());
        });
        payload.exclusions().forEach(values::add);
        payload.violations().forEach(violation -> {
            values.add(violation.code());
            values.add(violation.basket_item_id());
        });
        payload.trailLegs().forEach(leg -> {
            values.add(leg.from_basket_item_id());
            values.add(leg.to_basket_item_id());
            values.add(leg.travel_mode());
            values.add(leg.duration_minutes());
            values.add(leg.distance_meters());
            values.add(leg.encoded_polyline());
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.forEach(value -> updateDigest(digest, value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void updateDigest(MessageDigest digest, Object value) {
        byte[] bytes = value == null
            ? new byte[0]
            : value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) 0);
    }

    private record FingerprintPayload(
        long tripId,
        long version,
        List<TravelGuideAiResponses.Action> actions,
        List<Long> exclusions,
        List<TravelGuideAiResponses.Violation> violations,
        List<TravelGuideAiResponses.TrailLeg> trailLegs
    ) {
        private FingerprintPayload {
            actions = List.copyOf(actions);
            exclusions = List.copyOf(exclusions);
            violations = List.copyOf(violations);
            trailLegs = List.copyOf(trailLegs);
        }
    }

    private static TravelGuideAiException badRequest(String code, String message) {
        return new TravelGuideAiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static TravelGuideAiException conflict(String code, String message) {
        return new TravelGuideAiException(HttpStatus.CONFLICT, code, message);
    }
}
