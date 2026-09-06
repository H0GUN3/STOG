package com.stog.backend.profile;

import com.stog.backend.plan.CurrentUserId;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("gcs-write")
@RequestMapping("/profile/avatar")
public class ProfileAvatarController {
    private final ProfileAvatarService avatars;

    public ProfileAvatarController(ProfileAvatarService avatars) {
        this.avatars = avatars;
    }

    @PostMapping("/upload-url")
    public ProfileResponses.AvatarUploadUrl uploadUrl(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ProfileRequests.AvatarUpload request
    ) {
        return avatars.issueUploadUrl(CurrentUserId.from(jwt), request);
    }

    @PostMapping("/finalize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finalizeUpload(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ProfileRequests.AvatarUpload request
    ) {
        avatars.finalizeUpload(CurrentUserId.from(jwt), request);
    }
}
