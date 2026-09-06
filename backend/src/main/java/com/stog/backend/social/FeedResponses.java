package com.stog.backend.social;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public final class FeedResponses {
    private FeedResponses() {
    }

    public record Page(List<Item> items, String next_cursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Item(
        long photo_id,
        long trip_id,
        long owner_id,
        String owner_nickname,
        URI owner_profile_image_url,
        String thumbnail_key,
        ThumbnailMedia thumbnail_media,
        String caption,
        String cell_id,
        Double lat,
        Double lng,
        Instant taken_at,
        long like_count,
        long comment_count,
        boolean liked_by_viewer,
        Instant created_at,
        boolean saved_by_viewer
    ) {
        public Item(
            long photo_id,
            long trip_id,
            long owner_id,
            String thumbnail_key,
            ThumbnailMedia thumbnail_media,
            String caption,
            String cell_id,
            Double lat,
            Double lng,
            Instant taken_at,
            long like_count,
            boolean liked_by_viewer,
            Instant created_at
        ) {
            this(
                photo_id, trip_id, owner_id, null, null, thumbnail_key, thumbnail_media,
                caption, cell_id, lat, lng, taken_at, like_count, 0, liked_by_viewer,
                created_at, false
            );
        }
    }

    public record ThumbnailMedia(String state, URI signed_url) {
        public ThumbnailMedia {
            if (!List.of("available", "unavailable", "failed").contains(state)) {
                throw new IllegalArgumentException("thumbnail media state is invalid");
            }
            if (("available".equals(state)) != (signed_url != null)) {
                throw new IllegalArgumentException("signed thumbnail URL does not match state");
            }
        }

        public static ThumbnailMedia available(URI signedUrl) {
            return new ThumbnailMedia("available", signedUrl);
        }

        public static ThumbnailMedia unavailable() {
            return new ThumbnailMedia("unavailable", null);
        }

        public static ThumbnailMedia failed() {
            return new ThumbnailMedia("failed", null);
        }
    }
}
