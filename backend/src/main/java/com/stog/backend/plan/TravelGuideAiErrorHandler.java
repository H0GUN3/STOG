package com.stog.backend.plan;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TravelGuideAiErrorHandler {
    @ExceptionHandler(TravelGuideAiException.class)
    ResponseEntity<ErrorResponse> proposalFailure(TravelGuideAiException error) {
        return ResponseEntity.status(error.getStatusCode()).body(
            new ErrorResponse(error.code(), error.getReason(), false, UUID.randomUUID().toString())
        );
    }

    public record ErrorResponse(
        String code,
        String message,
        boolean retryable,
        String request_id
    ) {
    }
}
