package com.stog.backend.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PhotoApiException extends ResponseStatusException {
    private final String code;

    PhotoApiException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
