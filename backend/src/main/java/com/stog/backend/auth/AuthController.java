package com.stog.backend.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService auth;
    private final SocialAuthService social;
    private final AuthProperties properties;

    public AuthController(
        AuthService auth,
        SocialAuthService social,
        AuthProperties properties
    ) {
        this.auth = auth;
        this.social = social;
        this.properties = properties;
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody AuthRequests.Signup request) {
        return response(auth.signup(request.email(), request.password(), request.nickname()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequests.Login request) {
        return response(auth.login(request.email(), request.password()));
    }

    @PostMapping("/social")
    public AuthResponse social(@Valid @RequestBody AuthRequests.Social request) {
        return response(social.login(request.provider(), request.token()));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody AuthRequests.Refresh request) {
        return response(auth.refresh(request.refresh_token()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthRequests.Logout request) {
        auth.logout(request.refresh_token());
        return ResponseEntity.noContent().build();
    }

    private AuthResponse response(AuthSession session) {
        return AuthResponse.from(
            session,
            properties.accessTokenTtl().toSeconds(),
            properties.refreshTokenTtl().toSeconds()
        );
    }
}
