package com.stog.backend.compat;

/** Typed provider failure retained in the refresh outcome without provider internals. */
public final class CatalogRefreshProviderException extends RuntimeException {
    private final String code;

    public CatalogRefreshProviderException(String code) {
        super(code);
        this.code = code;
    }

    public CatalogRefreshProviderException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
