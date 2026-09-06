package com.stog.backend.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TravelGuideAiException extends ResponseStatusException {
    private final String code;

    public TravelGuideAiException(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
