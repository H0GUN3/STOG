package com.stog.backend.google;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GoogleProviderErrorHandler {
    @ExceptionHandler(GoogleProviderException.class)
    ResponseEntity<ApiError> providerFailure(GoogleProviderException error) {
        return ResponseEntity
            .status(error.status())
            .body(new ApiError(
                error.code(),
                "Google provider request failed",
                error.retryable(),
                UUID.randomUUID().toString()
            ));
    }

    public record ApiError(
        String code,
        String message,
        boolean retryable,
        String request_id
    ) {
    }
}
