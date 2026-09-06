package com.stog.backend.profile;

import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ProfileRequests {
    private ProfileRequests() {
    }

    public record Update(
        @Size(max = 80) String nickname,
        Map<String, Double> preference_scores,
        Map<String, Double> travel_style_scores
    ) {
        public Update(
            Map<String, Double> preference_scores,
            Map<String, Double> travel_style_scores
        ) {
            this(null, preference_scores, travel_style_scores);
        }
    }

    public record AvatarUpload(
        @NotBlank String client_upload_id,
        @NotBlank String content_type,
        @NotNull @jakarta.validation.constraints.Positive Long size_bytes,
        @NotBlank String sha256
    ) {
    }

    public record Survey(
        @NotBlank String survey_version,
        @NotBlank String survey_type,
        @NotNull Map<String, Object> answers
    ) {
    }
}
