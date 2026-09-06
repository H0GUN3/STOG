package com.stog.backend.storage;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PhotoErrorHandler {
    @ExceptionHandler(PhotoApiException.class)
    ResponseEntity<ApiError> photoFailure(PhotoApiException error) {
        return ResponseEntity.status(error.getStatusCode()).body(
            new ApiError(new ErrorBody(
                error.code(), error.getReason(), error.getStatusCode().is5xxServerError(),
                UUID.randomUUID().toString()
            ))
        );
    }

    public record ApiError(ErrorBody error) {
    }

    public record ErrorBody(
        String code,
        String message,
        boolean retryable,
        String request_id
    ) {
    }
}
