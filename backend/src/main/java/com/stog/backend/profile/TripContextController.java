package com.stog.backend.profile;

import com.stog.backend.plan.CurrentUserId;
import java.util.Map;
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
public class TripContextController {
    private final TripContextService contexts;

    public TripContextController(TripContextService contexts) {
        this.contexts = contexts;
    }

    @GetMapping("/{tripId}/context")
    public Map<String, Object> get(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return contexts.get(CurrentUserId.from(jwt), tripId);
    }

    @PatchMapping("/{tripId}/context")
    public Map<String, Object> replace(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @RequestBody Map<String, Object> values
    ) {
        return contexts.replace(CurrentUserId.from(jwt), tripId, values);
    }
}
