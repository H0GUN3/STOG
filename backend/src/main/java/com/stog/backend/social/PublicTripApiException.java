package com.stog.backend.social;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PublicTripApiException extends ResponseStatusException {
    private final String code;

    PublicTripApiException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
