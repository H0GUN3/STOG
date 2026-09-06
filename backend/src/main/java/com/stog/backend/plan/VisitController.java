package com.stog.backend.plan;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/trips")
public class VisitController {
    private final VisitService visits;

    public VisitController(VisitService visits) {
        this.visits = visits;
    }

    @PostMapping("/{tripId}/visits")
    public VisitResponses.Recorded record(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody VisitRequests.Record request
    ) {
        return visits.record(CurrentUserId.from(jwt), tripId, request);
    }
}
