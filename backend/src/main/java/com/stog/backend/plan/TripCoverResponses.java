package com.stog.backend.plan;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public final class TripCoverResponses {
    private TripCoverResponses() {
    }

    public record UploadUrl(
        String object_key,
        URI upload_url,
        String content_type,
        Map<String, String> upload_headers,
        Instant expires_at
    ) {
        public UploadUrl {
            upload_headers = Map.copyOf(upload_headers);
        }
    }
}
