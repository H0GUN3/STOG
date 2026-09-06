package com.stog.backend.profile;

import com.stog.backend.plan.CurrentUserId;
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
@RequestMapping("/profile")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public ProfileResponses.Profile get(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(CurrentUserId.from(jwt));
    }

    @GetMapping("/summary")
    public ProfileResponses.Summary summary(@AuthenticationPrincipal Jwt jwt) {
        return profiles.summary(CurrentUserId.from(jwt));
    }

    @PutMapping
    public ProfileResponses.Profile update(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ProfileRequests.Update request
    ) {
        return profiles.update(CurrentUserId.from(jwt), request);
    }

    @PutMapping("/survey")
    public ProfileResponses.Survey submitSurvey(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ProfileRequests.Survey request
    ) {
        return profiles.submitSurvey(CurrentUserId.from(jwt), request);
    }
}
