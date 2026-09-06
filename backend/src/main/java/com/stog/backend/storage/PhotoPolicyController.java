package com.stog.backend.storage;

import com.stog.backend.plan.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/photos")
public class PhotoPolicyController {
    private final PhotoPolicyService policy;

    public PhotoPolicyController(PhotoPolicyService policy) {
        this.policy = policy;
    }

    @PostMapping("/{id}/public-grants")
    public PhotoResponses.PublicGrant acceptPublicGrant(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return policy.acceptPublicGrant(CurrentUserId.from(jwt), id);
    }

    @PostMapping("/{id}/public-grants/{version}/revoke")
    public PhotoResponses.PublicGrant revokePublicGrant(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @PathVariable int version
    ) {
        return policy.revokePublicGrant(CurrentUserId.from(jwt), id, version);
    }

    @GetMapping("/{id}/public-grants")
    public List<PhotoResponses.PublicGrant> grants(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return policy.grants(CurrentUserId.from(jwt), id);
    }

    @PatchMapping("/{id}/moderation")
    public PhotoResponses.Moderation moderate(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody PhotoRequests.Moderation request
    ) {
        return policy.moderate(CurrentUserId.from(jwt), id, request);
    }
}
