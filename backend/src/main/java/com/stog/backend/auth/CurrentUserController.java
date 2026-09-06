package com.stog.backend.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {
    private final CurrentUserService currentUser;

    public CurrentUserController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(currentUser.find(Long.valueOf(jwt.getSubject())));
    }
}
