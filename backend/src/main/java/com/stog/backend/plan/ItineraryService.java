package com.stog.backend.plan;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItineraryService {
    private final TripMembershipPolicy memberships;
    private final ItineraryValidator validator;
    private final ItineraryRepository itineraries;

    public ItineraryService(
        TripMembershipPolicy memberships,
        ItineraryValidator validator,
        ItineraryRepository itineraries
    ) {
        this.memberships = memberships;
        this.validator = validator;
        this.itineraries = itineraries;
    }

    @Transactional(readOnly = true)
    public ItineraryResponses.Items get(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        return new ItineraryResponses.Items(
            itineraries.currentVersion(tripId),
            itineraries.findByTrip(tripId)
        );
    }

    @Transactional
    public ItineraryResponses.Items replace(
        long userId,
        long tripId,
        ItineraryRequests.Replace request
    ) {
        memberships.requireActiveMember(userId, tripId);
        validator.validate(tripId, request.items());
        List<ItineraryResponses.Item> items = itineraries.replace(
            userId, tripId, request.items()
        );
        return new ItineraryResponses.Items(itineraries.currentVersion(tripId), items);
    }

    @Transactional(readOnly = true)
    public ItineraryResponses.Changes changes(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        return new ItineraryResponses.Changes(itineraries.findChangesByTrip(tripId));
    }
}
