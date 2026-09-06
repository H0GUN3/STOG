package com.stog.backend.auth;

public class InvalidOauthStateException extends RuntimeException {
    public InvalidOauthStateException() {
        super("OAuth state is invalid, expired, or consumed");
    }
}
