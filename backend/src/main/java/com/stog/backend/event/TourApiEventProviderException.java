package com.stog.backend.event;

public class TourApiEventProviderException extends RuntimeException {
    private final String code;

    public TourApiEventProviderException(String code) {
        super(code);
        this.code = code;
    }

    public TourApiEventProviderException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
