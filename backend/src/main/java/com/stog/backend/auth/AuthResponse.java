package com.stog.backend.auth;

public record AuthResponse(
    String access_token,
    String refresh_token,
    long access_token_expires_in,
    long refresh_token_expires_in,
    UserResponse user
) {
    public static AuthResponse from(
        AuthSession session,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn
    ) {
        return new AuthResponse(
            session.accessToken(),
            session.refreshToken(),
            accessTokenExpiresIn,
            refreshTokenExpiresIn,
            UserResponse.from(session.user())
        );
    }
}
