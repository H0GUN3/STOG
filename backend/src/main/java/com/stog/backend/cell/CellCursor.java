package com.stog.backend.cell;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record CellCursor(long cellId) {
    private static final String VERSION = "v1";

    static CellCursor decode(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.US_ASCII
            );
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2 || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            return new CellCursor(CellIdCalculator.fromWire(parts[1]));
        } catch (RuntimeException error) {
            if (error instanceof ResponseStatusException response) {
                throw response;
            }
            throw invalid();
        }
    }

    String encode() {
        String value = VERSION + "|" + CellIdCalculator.toWire(cellId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cell cursor is invalid");
    }
}
