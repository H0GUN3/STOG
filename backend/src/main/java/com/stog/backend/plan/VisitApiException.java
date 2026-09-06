package com.stog.backend.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class VisitApiException extends ResponseStatusException {
    private final String code;

    VisitApiException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
