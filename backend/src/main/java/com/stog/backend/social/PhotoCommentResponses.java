package com.stog.backend.social;

import java.time.Instant;
import java.util.List;

public final class PhotoCommentResponses {
    private PhotoCommentResponses() {
    }

    public record Comment(
        long id,
        long photo_id,
        long user_id,
        String author_name,
        String body,
        Instant created_at
    ) {
    }

    public record Page(List<Comment> items) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
