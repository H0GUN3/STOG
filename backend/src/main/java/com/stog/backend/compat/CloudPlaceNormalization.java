package com.stog.backend.compat;

import java.util.List;

public record CloudPlaceNormalization(
    CloudPlaceSourceRow sourceRow,
    String classification,
    String reason,
    String provider,
    String externalId,
    String canonicalCellId,
    List<String> diagnostics
) {
    public CloudPlaceNormalization {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
