package com.stog.backend.storage;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class PhotoRequests {
    private PhotoRequests() {
    }

    public record UploadObject(
        @NotBlank String content_type,
        @NotNull @Positive Long size_bytes,
        @NotBlank String sha256
    ) {
    }

    public record UploadUrl(
        @NotNull Long trip_id,
        @NotBlank String client_upload_id,
        @NotNull @Valid UploadObject original,
        @NotNull @Valid UploadObject thumbnail
    ) {
    }

    public record Create(
        @NotNull Long trip_id,
        @NotBlank String source,
        @NotBlank String client_upload_id,
        @NotBlank String original_key,
        @NotBlank String thumb_key,
        @NotNull @Valid UploadObject original,
        @NotNull @Valid UploadObject thumbnail,
        Double latitude,
        Double longitude,
        Double accuracy_m,
        String location_provenance,
        Instant taken_at,
        @Size(max = 2000) String caption,
        String place_resolution_status,
        Long expected_place_id,
        String visibility,
        Boolean public_consent
    ) {
        public Create(
            Long trip_id,
            String source,
            String client_upload_id,
            String original_key,
            String thumb_key,
            UploadObject original,
            UploadObject thumbnail,
            Double latitude,
            Double longitude,
            Instant taken_at,
            String caption
        ) {
            this(
                trip_id, source, client_upload_id, original_key, thumb_key,
                original, thumbnail, latitude, longitude, null, null, taken_at,
                caption, null, null, "private", false
            );
        }

        UploadUrl uploadRequest() {
            return new UploadUrl(trip_id, client_upload_id, original, thumbnail);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unsupported photo field: " + name);
        }
    }

    public record PlacePreview(
        @NotNull Double latitude,
        @NotNull Double longitude,
        Double accuracy_m
    ) {
    }

    public record Visibility(
        @NotBlank String visibility
    ) {
    }

    public record Moderation(
        @NotBlank String to_status,
        @Size(max = 2000) String reason
    ) {
    }
}
