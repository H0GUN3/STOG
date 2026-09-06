package com.stog.backend.auth;

public record SocialProfile(
    String provider,
    String providerUserId,
    String nickname
) {
}
