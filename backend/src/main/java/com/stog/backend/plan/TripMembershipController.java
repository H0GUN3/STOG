package com.stog.backend.plan;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping
public class TripMembershipController {
    private final TripMembershipService memberships;

    public TripMembershipController(TripMembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping("/trips/{tripId}/members")
    public TripMembershipResponses.Members members(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return memberships.members(CurrentUserId.from(jwt), tripId);
    }

    @PostMapping("/trips/{tripId}/invite-links")
    public TripMembershipResponses.InviteLink createInvite(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return memberships.createInvite(CurrentUserId.from(jwt), tripId);
    }

    @PostMapping("/trip-invites/{token}/join")
    public TripMembershipResponses.Joined join(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String token
    ) {
        return memberships.join(CurrentUserId.from(jwt), token);
    }

    @DeleteMapping("/trips/{tripId}")
    public ResponseEntity<Void> deleteTrip(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        memberships.deleteTrip(CurrentUserId.from(jwt), tripId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trips/{tripId}/members/{userId}")
    public ResponseEntity<Void> remove(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @PathVariable long userId
    ) {
        memberships.remove(CurrentUserId.from(jwt), tripId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trips/{tripId}/members/me")
    public ResponseEntity<Void> leave(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        memberships.leave(CurrentUserId.from(jwt), tripId);
        return ResponseEntity.noContent().build();
    }
}
