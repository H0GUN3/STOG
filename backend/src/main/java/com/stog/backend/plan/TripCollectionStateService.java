package com.stog.backend.plan;

import java.time.Clock;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripCollectionStateService {
    private final TripCollectionStateRepository states;
    private final TripRepository trips;
    private final TripMembershipPolicy memberships;
    private final Clock clock;

    public TripCollectionStateService(
        TripCollectionStateRepository states,
        TripRepository trips,
        TripMembershipPolicy memberships,
        Clock clock
    ) {
        this.states = states;
        this.trips = trips;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TripCollectionResponses.State get(long actorId, long tripId) {
        memberships.requireActiveMember(actorId, tripId);
        return states.find(tripId, actorId)
            .orElseThrow(() -> new IllegalStateException(
                "Active trip member is missing collector state"
            ));
    }

    @Transactional
    public TripCollectionResponses.State update(
        long actorId,
        long tripId,
        TripCollectionRequests.Update request
    ) {
        memberships.requireActiveMember(actorId, tripId);
        if (("starting".equals(request.collector_state())
            || "active".equals(request.collector_state()))
            && !"granted".equals(request.permission_state())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A starting or active collector requires granted permission"
            );
        }
        TripRepository.VisitLifecycle lifecycle = trips.lockVisitLifecycle(tripId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Trip access denied"
            ));
        if ("ended".equals(lifecycle.mode())
            && !"ended".equals(request.collector_state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trip is ended");
        }

        TripCollectionResponses.State current = states.find(tripId, actorId)
            .orElseThrow(() -> new IllegalStateException(
                "Active trip member is missing collector state"
            ));
        if (current.mode_version() != request.expected_mode_version()) {
            throw staleVersion();
        }
        if (current.collector_state().equals(request.collector_state())
            && current.permission_state().equals(request.permission_state())
            && Objects.equals(current.sync_cursor(), request.sync_cursor())) {
            return current;
        }

        TripCollectionResponses.State updated = states.update(tripId, actorId, request)
            .orElseThrow(this::staleVersion);
        if ("active".equals(updated.collector_state())) {
            trips.activateIfDormant(tripId, clock.instant());
        }
        return updated;
    }

    private ResponseStatusException staleVersion() {
        return new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Collector mode_version is stale"
        );
    }
}
