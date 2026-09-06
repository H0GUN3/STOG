package com.stog.backend.plan;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/trips")
public class TripCollectionStateController {
    private final TripCollectionStateService collectionStates;

    public TripCollectionStateController(TripCollectionStateService collectionStates) {
        this.collectionStates = collectionStates;
    }

    @GetMapping("/{tripId}/collection-state")
    public TripCollectionResponses.State get(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return collectionStates.get(CurrentUserId.from(jwt), tripId);
    }

    @PatchMapping("/{tripId}/collection-state")
    public TripCollectionResponses.State update(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TripCollectionRequests.Update request
    ) {
        return collectionStates.update(CurrentUserId.from(jwt), tripId, request);
    }
}
