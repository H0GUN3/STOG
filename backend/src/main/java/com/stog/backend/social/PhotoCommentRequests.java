package com.stog.backend.social;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PhotoCommentRequests {
    private PhotoCommentRequests() {
    }

    public record Create(
        @NotBlank @Size(max = 500) String body
    ) {
    }
}
