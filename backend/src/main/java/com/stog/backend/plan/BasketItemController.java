package com.stog.backend.plan;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/basket-items")
public class BasketItemController {
    private final PlanningService planning;

    public BasketItemController(PlanningService planning) {
        this.planning = planning;
    }

    @PostMapping
    public BasketResponses.Added addPlace(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody BasketRequests.AddPlace request
    ) {
        return planning.addPlace(CurrentUserId.from(jwt), request);
    }

    @PostMapping("/link")
    public BasketResponses.LinkAdded addLink(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody BasketRequests.AddLink request
    ) {
        return planning.addLink(CurrentUserId.from(jwt), request);
    }
}
