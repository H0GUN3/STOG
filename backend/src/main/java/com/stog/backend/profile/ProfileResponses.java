package com.stog.backend.profile;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public final class ProfileResponses {
    private ProfileResponses() {
    }

    public record Profile(
        Map<String, Double> preference_scores,
        Map<String, Double> travel_style_scores
    ) {
        public Profile {
            preference_scores = Map.copyOf(preference_scores);
            travel_style_scores = Map.copyOf(travel_style_scores);
        }
    }

    public record Summary(
        long user_id,
        String nickname,
        URI profile_image_url,
        long trip_count,
        long visited_cell_count,
        long photo_count,
        long honey_balance
    ) {
    }

    public record AvatarUploadUrl(
        String object_key,
        URI upload_url,
        String content_type,
        Map<String, String> upload_headers,
        Instant expires_at
    ) {
        public AvatarUploadUrl {
            upload_headers = Map.copyOf(upload_headers);
        }
    }

    public record Survey(
        String survey_version,
        String survey_type,
        boolean canonical,
        Map<String, Double> preference_scores,
        Map<String, Double> travel_style_scores
    ) {
        public Survey {
            preference_scores = Map.copyOf(preference_scores);
            travel_style_scores = Map.copyOf(travel_style_scores);
        }
    }
}
