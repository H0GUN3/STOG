package com.stog.backend.social;

import com.stog.backend.plan.TripMembershipPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicTripLikeService {
    private final PublicTripRepository trips;
    private final PublicTripLikeRepository likes;
    private final TripMembershipPolicy memberships;

    public PublicTripLikeService(
        PublicTripRepository trips,
        PublicTripLikeRepository likes,
        TripMembershipPolicy memberships
    ) {
        this.trips = trips;
        this.likes = likes;
        this.memberships = memberships;
    }

    @Transactional
    public PublicTripResponses.LikeState like(long userId, long tripId) {
        memberships.requireKnownUser(userId);
        trips.lockEligible(tripId);
        likes.insert(userId, tripId);
        return new PublicTripResponses.LikeState(tripId, likes.count(tripId), true);
    }

    @Transactional
    public PublicTripResponses.LikeState unlike(long userId, long tripId) {
        memberships.requireKnownUser(userId);
        trips.lockEligible(tripId);
        likes.delete(userId, tripId);
        return new PublicTripResponses.LikeState(tripId, likes.count(tripId), false);
    }
}
