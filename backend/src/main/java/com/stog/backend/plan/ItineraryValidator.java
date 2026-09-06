package com.stog.backend.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ItineraryValidator {
    private final TripRepository trips;
    private final BasketItemRepository basketItems;
    private final ItineraryRepository itineraries;

    public ItineraryValidator(
        TripRepository trips,
        BasketItemRepository basketItems,
        ItineraryRepository itineraries
    ) {
        this.trips = trips;
        this.basketItems = basketItems;
        this.itineraries = itineraries;
    }

    public void validate(long tripId, List<ItineraryRequests.Item> items) {
        Set<Long> basketItemIds = new HashSet<>();
        Set<String> positions = new HashSet<>();
        int dayCount = trips.dayCount(tripId).orElse(Integer.MAX_VALUE);
        for (ItineraryRequests.Item item : items) {
            if (item.day_number() > dayCount) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary day is outside the trip date range"
                );
            }
            if (!basketItemIds.add(item.basket_item_id())
                || !positions.add(item.day_number() + ":" + item.order_index())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Itinerary items must have unique basket items and positions"
                );
            }
        }
        if (!basketItems.allBelongToTrip(tripId, List.copyOf(basketItemIds))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Basket item does not belong to trip"
            );
        }
        Map<Long, Boolean> requestedFixed = items.stream().collect(
            java.util.stream.Collectors.toMap(
                ItineraryRequests.Item::basket_item_id,
                ItineraryRequests.Item::is_fixed
            )
        );
        for (Long fixedBasketItemId : itineraries.fixedBasketItemIds(tripId)) {
            if (!Boolean.TRUE.equals(requestedFixed.get(fixedBasketItemId))) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Fixed itinerary item cannot be removed or unfixed"
                );
            }
        }
    }
}
