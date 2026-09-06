package com.stog.backend.cell;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public final class CellResponses {
    private CellResponses() {
    }

    public record Page(List<Summary> items, String next_cursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Coordinate(double lat, double lng) {
    }

    public record Summary(
        String cell_id,
        String background,
        List<String> badges,
        String landmark_name,
        String landmark_image_url,
        long landmark_count,
        long public_photo_count,
        long public_photo_like_count,
        Long top_photo_id,
        long my_visit_count,
        long my_photo_count,
        URI my_latest_photo_thumbnail_url,
        Coordinate centroid,
        List<Coordinate> boundary
    ) {
        public Summary {
            badges = List.copyOf(badges);
            boundary = List.copyOf(boundary);
        }
    }

    public record Detail(
        String cell_id,
        String background,
        List<String> badges,
        String landmark_name,
        String landmark_image_url,
        long landmark_count,
        long public_photo_count,
        long public_photo_like_count,
        Long top_photo_id,
        long my_visit_count,
        long my_photo_count,
        URI my_latest_photo_thumbnail_url,
        List<String> visibility_reasons,
        Coordinate centroid,
        List<Coordinate> boundary
    ) {
        public Detail {
            badges = List.copyOf(badges);
            visibility_reasons = List.copyOf(visibility_reasons);
            boundary = List.copyOf(boundary);
        }
    }

    public record PhotoPage(List<Photo> items, String next_cursor) {
        public PhotoPage {
            items = List.copyOf(items);
        }
    }

    public record Photo(
        long id,
        String cell_id,
        double lat,
        double lng,
        URI thumbnail_url,
        Instant taken_at,
        String caption,
        long like_count,
        boolean liked_by_viewer,
        String visibility_scope,
        Double accuracy_m,
        Long place_id,
        String place_name,
        String place_resolution_status,
        String visibility,
        String moderation_status,
        String publication_status
    ) {
    }
}
