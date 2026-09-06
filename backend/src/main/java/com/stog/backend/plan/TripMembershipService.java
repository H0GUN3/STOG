package com.stog.backend.plan;

import com.stog.backend.auth.RandomTokenGenerator;
import com.stog.backend.auth.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripMembershipService {
    private final TripMembershipRepository memberships;
    private final TripMembershipPolicy policy;
    private final RandomTokenGenerator tokens;
    private final TokenHasher tokenHasher;

    public TripMembershipService(
        TripMembershipRepository memberships,
        TripMembershipPolicy policy,
        RandomTokenGenerator tokens,
        TokenHasher tokenHasher
    ) {
        this.memberships = memberships;
        this.policy = policy;
        this.tokens = tokens;
        this.tokenHasher = tokenHasher;
    }

    @Transactional(readOnly = true)
    public TripMembershipResponses.Members members(long actorId, long tripId) {
        policy.requireActiveMember(actorId, tripId);
        TripMembershipRepository.MembersView view = memberships.findActiveMembers(tripId)
            .orElseThrow(() -> notFound("Trip not found"));
        return new TripMembershipResponses.Members(actorId, view.ownerId(), view.members());
    }

    @Transactional
    public TripMembershipResponses.InviteLink createInvite(long actorId, long tripId) {
        policy.requireActiveMember(actorId, tripId);
        String token = tokens.generate();
        memberships.markAsGroup(tripId);
        memberships.createInvite(tripId, actorId, tokenHasher.hash(token));
        return new TripMembershipResponses.InviteLink(
            token,
            "/trip-invites/" + token + "/join"
        );
    }

    @Transactional
    public TripMembershipResponses.Joined join(long actorId, String token) {
        policy.requireKnownUser(actorId);
        TripMembershipRepository.LockedInvite invite = policy.requireJoinableInvite(
            tokenHasher.hash(token)
        );
        if (invite.ownerId() != actorId) {
            memberships.activateMember(invite.tripId(), actorId);
        }
        return new TripMembershipResponses.Joined(invite.tripId());
    }

    @Transactional
    public void remove(long actorId, long tripId, long userId) {
        TripMembershipRepository.LockedTrip trip = memberships.lockTrip(tripId)
            .orElseThrow(() -> notFound("Trip not found"));
        policy.requireOwner(actorId, trip);
        if (trip.ownerId() == userId) {
            throw badRequest("The owner must leave through the leave endpoint");
        }
        if (!memberships.deactivateMember(tripId, userId)) {
            throw notFound("Active member not found");
        }
    }

    @Transactional
    public void leave(long actorId, long tripId) {
        TripMembershipRepository.LockedTrip trip = memberships.lockTrip(tripId)
            .orElseThrow(() -> notFound("Trip not found"));
        policy.requireActiveMember(actorId, trip);
        if (trip.ownerId() == actorId) {
            if (memberships.activeNonOwnerCount(tripId, actorId) != 0) {
                throw badRequest("The owner cannot leave while active members remain");
            }
            memberships.deleteTrip(tripId);
            return;
        }
        if (!memberships.deactivateMember(tripId, actorId)) {
            throw notFound("Active member not found");
        }
    }

    @Transactional
    public void deleteTrip(long actorId, long tripId) {
        TripMembershipRepository.LockedTrip trip = memberships.lockTrip(tripId)
            .orElseThrow(() -> notFound("Trip not found"));
        policy.requireOwner(actorId, trip);
        if (memberships.activeNonOwnerCount(tripId, actorId) != 0) {
            throw badRequest("The owner cannot delete while active members remain");
        }
        memberships.deleteTrip(tripId);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
