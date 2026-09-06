package com.stog.backend.google;

import org.springframework.http.HttpStatus;

public class GoogleProviderException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public GoogleProviderException(
        HttpStatus status,
        String code,
        boolean retryable
    ) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
