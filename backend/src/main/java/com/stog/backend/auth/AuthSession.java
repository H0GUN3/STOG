package com.stog.backend.auth;

public record AuthSession(
    String accessToken,
    String refreshToken,
    User user
) {
}
