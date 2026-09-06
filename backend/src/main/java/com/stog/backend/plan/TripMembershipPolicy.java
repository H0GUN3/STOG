package com.stog.backend.plan;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TripMembershipPolicy {
    private final TripMembershipRepository memberships;

    public TripMembershipPolicy(TripMembershipRepository memberships) {
        this.memberships = memberships;
    }

    public void requireKnownUser(long actorId) {
        if (!memberships.userExists(actorId)) {
            throw denied();
        }
    }

    public void requireOwner(long actorId, long tripId) {
        if (!memberships.isOwner(actorId, tripId)) {
            throw denied();
        }
    }

    public void requireOwner(long actorId, TripMembershipRepository.LockedTrip trip) {
        if (trip.ownerId() != actorId) {
            throw denied();
        }
    }

    public void requireActiveMember(long actorId, long tripId) {
        if (!memberships.isActiveMember(actorId, tripId)) {
            throw denied();
        }
    }

    public void requireActiveMember(
        long actorId,
        TripMembershipRepository.LockedTrip trip
    ) {
        if (trip.ownerId() != actorId && !memberships.isActiveMember(actorId, trip.id())) {
            throw denied();
        }
    }

    public void requireVisitMember(
        long actorId,
        long tripId,
        Instant enteredAt,
        Instant leftAt
    ) {
        if (!memberships.isVisitMember(actorId, tripId, enteredAt, leftAt)) {
            throw denied();
        }
    }

    public TripMembershipRepository.LockedInvite requireJoinableInvite(String tokenHash) {
        return memberships.lockInvite(tokenHash).orElseThrow(this::denied);
    }

    private ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Trip access denied");
    }
}
