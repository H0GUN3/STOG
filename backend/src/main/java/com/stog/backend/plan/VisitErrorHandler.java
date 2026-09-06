package com.stog.backend.plan;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class VisitErrorHandler {
    @ExceptionHandler(VisitApiException.class)
    ResponseEntity<ApiError> visitFailure(VisitApiException error) {
        return ResponseEntity
            .status(error.getStatusCode())
            .body(new ApiError(new ApiErrorBody(
                error.code(),
                error.getReason(),
                false,
                UUID.randomUUID().toString()
            )));
    }

    public record ApiError(ApiErrorBody error) {
    }

    public record ApiErrorBody(
        String code,
        String message,
        boolean retryable,
        String request_id
    ) {
    }
}
