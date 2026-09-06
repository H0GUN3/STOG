package com.stog.backend.cell;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stog.cell")
public record CellProperties(int summaryPageSize, int photoPageSize) {
    public CellProperties {
        if (summaryPageSize < 1 || summaryPageSize == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("summaryPageSize must be a positive bounded integer");
        }
        if (photoPageSize < 1 || photoPageSize == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("photoPageSize must be a positive bounded integer");
        }
    }
}
