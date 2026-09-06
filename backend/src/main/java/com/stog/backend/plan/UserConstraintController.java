package com.stog.backend.plan;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/me/constraints")
public class UserConstraintController {
    private final UserConstraintService constraints;

    public UserConstraintController(UserConstraintService constraints) {
        this.constraints = constraints;
    }

    @GetMapping
    public UserConstraintResponses.Values get(@AuthenticationPrincipal Jwt jwt) {
        return constraints.get(CurrentUserId.from(jwt));
    }

    @PutMapping
    public UserConstraintResponses.Values replace(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UserConstraintRequests.Replace request
    ) {
        return constraints.replace(CurrentUserId.from(jwt), request);
    }
}
