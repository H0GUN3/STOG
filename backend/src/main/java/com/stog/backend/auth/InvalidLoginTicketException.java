package com.stog.backend.auth;

public class InvalidLoginTicketException extends RuntimeException {
    public InvalidLoginTicketException() {
        super("Login ticket is invalid, expired, or consumed");
    }
}
