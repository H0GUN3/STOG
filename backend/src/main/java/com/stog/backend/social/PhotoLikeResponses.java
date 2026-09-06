package com.stog.backend.social;

public final class PhotoLikeResponses {
    private PhotoLikeResponses() {
    }

    public record State(long photo_id, long like_count, boolean liked_by_viewer) {
    }
}
