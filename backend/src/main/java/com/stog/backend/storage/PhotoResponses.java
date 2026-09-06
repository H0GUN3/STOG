package com.stog.backend.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public final class PhotoResponses {
    private PhotoResponses() {
    }

    public record UploadUrl(
        String original_object_key,
        URI original_upload_url,
        String thumbnail_object_key,
        URI thumbnail_upload_url,
        String content_type,
        Map<String, String> original_upload_headers,
        Map<String, String> thumbnail_upload_headers,
        Instant expires_at
    ) {
    }

    public record ReadUrls(URI original_url, URI thumbnail_url) {
    }

    public record PlacePreview(
        String status,
        Long place_id,
        String place_name
    ) {
    }

    public record Detail(
        long id,
        Long trip_id,
        long user_id,
        String source,
        String cell_id,
        Double latitude,
        Double longitude,
        Double accuracy_m,
        String location_provenance,
        Instant taken_at,
        URI original_url,
        URI thumbnail_url,
        String caption,
        Long place_id,
        String place_name,
        String place_resolution_status,
        String visibility,
        String moderation_status,
        boolean public_consent,
        String publication_status,
        Instant created_at
    ) {
    }

    public record ArchiveItem(
        long id,
        Long trip_id,
        long user_id,
        String source,
        String cell_id,
        Double latitude,
        Double longitude,
        Double accuracy_m,
        String location_provenance,
        Instant taken_at,
        URI thumbnail_url,
        String caption,
        Long place_id,
        String place_name,
        String place_resolution_status,
        String visibility,
        String moderation_status,
        boolean public_consent,
        String publication_status,
        Instant created_at
    ) {
    }

    public record PublicGrant(
        long photo_id,
        int version,
        long granted_by,
        Instant granted_at,
        Instant revoked_at,
        java.util.List<String> scope
    ) {
    }

    public record Moderation(
        long photo_id,
        String from_status,
        String to_status,
        long moderator_id,
        String reason,
        Instant created_at
    ) {
    }
}
