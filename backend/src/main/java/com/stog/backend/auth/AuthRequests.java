package com.stog.backend.auth;

import jakarta.validation.constraints.NotBlank;

public final class AuthRequests {
    private AuthRequests() {
    }

    public record Signup(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String nickname
    ) {
    }

    public record Login(
        @NotBlank String email,
        @NotBlank String password
    ) {
    }

    public record Refresh(@NotBlank String refresh_token) {
    }

    public record Logout(@NotBlank String refresh_token) {
    }

    public record Social(
        @NotBlank String provider,
        @NotBlank String token
    ) {
    }
}
