package com.stog.backend.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthErrorHandler {
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException error) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", error.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ApiError> invalidRefresh(InvalidRefreshTokenException error) {
        return error(
            HttpStatus.UNAUTHORIZED,
            "AUTH_INVALID_REFRESH_TOKEN",
            error.getMessage()
        );
    }

    @ExceptionHandler(InvalidLoginTicketException.class)
    ResponseEntity<ApiError> invalidTicket(InvalidLoginTicketException error) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_LOGIN_TICKET", error.getMessage());
    }

    @ExceptionHandler(InvalidOauthStateException.class)
    ResponseEntity<ApiError> invalidState(InvalidOauthStateException error) {
        return error(HttpStatus.BAD_REQUEST, "AUTH_INVALID_OAUTH_STATE", error.getMessage());
    }

    private ResponseEntity<ApiError> error(
        HttpStatus status,
        String code,
        String message
    ) {
        return ResponseEntity.status(status).body(new ApiError(
            new ApiErrorBody(code, message, false, UUID.randomUUID().toString())
        ));
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
