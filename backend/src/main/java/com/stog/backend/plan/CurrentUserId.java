package com.stog.backend.plan;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public final class CurrentUserId {
    private CurrentUserId() {
    }

    public static long from(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User identity is invalid",
                error
            );
        }
    }
}
