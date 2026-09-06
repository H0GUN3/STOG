package com.stog.backend.cell;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record CellPhotoCursor(Instant createdAt, long photoId) {
    private static final String VERSION = "v1";

    static CellPhotoCursor decode(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.US_ASCII
            );
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            long seconds = Long.parseLong(parts[1]);
            int nanos = Integer.parseInt(parts[2]);
            long photoId = Long.parseLong(parts[3]);
            if (nanos < 0 || nanos > 999_999_999 || photoId < 1) {
                throw invalid();
            }
            return new CellPhotoCursor(Instant.ofEpochSecond(seconds, nanos), photoId);
        } catch (RuntimeException error) {
            if (error instanceof ResponseStatusException response) {
                throw response;
            }
            throw invalid();
        }
    }

    String encode() {
        String value = "%s|%d|%d|%d".formatted(
            VERSION,
            createdAt.getEpochSecond(),
            createdAt.getNano(),
            photoId
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cell photo cursor is invalid");
    }
}
