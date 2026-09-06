package com.stog.backend.auth;

public interface SocialProfileVerifier {
    SocialProfile verify(String token);
}
