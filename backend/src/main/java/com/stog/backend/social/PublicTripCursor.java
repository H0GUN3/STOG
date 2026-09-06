package com.stog.backend.social;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record PublicTripCursor(Instant endedAt, long tripId) {
    String encode() {
        String value = endedAt + "|" + tripId;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static PublicTripCursor decode(String value) {
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8
            );
            int separator = decoded.lastIndexOf('|');
            if (separator < 1) throw new IllegalArgumentException();
            return new PublicTripCursor(
                Instant.parse(decoded.substring(0, separator)),
                Long.parseLong(decoded.substring(separator + 1))
            );
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public trip cursor is invalid");
        }
    }
}
