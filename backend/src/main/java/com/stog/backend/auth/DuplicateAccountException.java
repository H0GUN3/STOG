package com.stog.backend.auth;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("Account already exists");
    }
}
