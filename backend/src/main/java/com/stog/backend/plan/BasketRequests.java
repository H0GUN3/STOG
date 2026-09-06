package com.stog.backend.plan;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public final class BasketRequests {
    private BasketRequests() {
    }

    public record AddPlace(
        @NotNull Long trip_id,
        @NotBlank String client_item_id,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payload_fingerprint,
        @NotBlank String provider,
        @NotBlank String external_id,
        @NotBlank String name,
        @NotBlank String category,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        Long canonical_place_id,
        Long canonical_source_id
    ) {
        public AddPlace(
            Long trip_id,
            String provider,
            String external_id,
            String name,
            String category,
            Double latitude,
            Double longitude
        ) {
            this(
                trip_id,
                UUID.randomUUID().toString(),
                BasketPayloadFingerprint.forPlace(
                    trip_id, provider, external_id, name, category, latitude, longitude,
                    null, null
                ),
                provider,
                external_id,
                name,
                category,
                latitude,
                longitude,
                null,
                null
            );
        }

        public AddPlace(
            Long trip_id,
            String provider,
            String external_id,
            String name,
            String category,
            Double latitude,
            Double longitude,
            Long canonical_place_id,
            Long canonical_source_id
        ) {
            this(
                trip_id,
                UUID.randomUUID().toString(),
                BasketPayloadFingerprint.forPlace(
                    trip_id, provider, external_id, name, category, latitude, longitude,
                    canonical_place_id, canonical_source_id
                ),
                provider,
                external_id,
                name,
                category,
                latitude,
                longitude,
                canonical_place_id,
                canonical_source_id
            );
        }
    }

    public record AddLink(
        @NotNull Long trip_id,
        @NotBlank String client_item_id,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payload_fingerprint,
        @NotBlank String source,
        @NotBlank String original_url,
        @NotBlank String title,
        String category
    ) {
        public AddLink(
            Long trip_id,
            String source,
            String original_url,
            String title,
            String category
        ) {
            this(
                trip_id,
                UUID.randomUUID().toString(),
                BasketPayloadFingerprint.forLink(
                    trip_id, source, original_url, title, category
                ),
                source,
                original_url,
                title,
                category
            );
        }
    }
}
