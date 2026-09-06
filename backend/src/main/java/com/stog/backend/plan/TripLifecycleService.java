package com.stog.backend.plan;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripLifecycleService {
    private final TripRepository trips;
    private final TripCollectionStateRepository collectionStates;
    private final TripMembershipPolicy memberships;
    private final Clock clock;

    public TripLifecycleService(
        TripRepository trips,
        TripCollectionStateRepository collectionStates,
        TripMembershipPolicy memberships,
        Clock clock
    ) {
        this.trips = trips;
        this.collectionStates = collectionStates;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Transactional
    public TripResponses.Mode update(long actorId, long tripId, TripRequests.Mode request) {
        memberships.requireOwner(actorId, tripId);
        TripRepository.LockedLifecycle trip = trips.lockLifecycle(tripId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Trip not found"
            ));
        if (trip.mode().equals(request.mode())) {
            return new TripResponses.Mode(
                trip.id(),
                trip.mode(),
                trip.startedAt(),
                trip.endedAt()
            );
        }
        if ("ended".equals(trip.mode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trip is ended");
        }
        if ("active".equals(request.mode()) && collectionStates.activeCount(tripId) == 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "An active collector is required before the trip becomes active"
            );
        }

        TripResponses.Mode updated = trips.updateMode(trip, request.mode(), clock.instant());
        if ("ended".equals(updated.mode())) {
            collectionStates.endAll(tripId);
        }
        return updated;
    }
}
