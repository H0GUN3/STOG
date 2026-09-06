package com.stog.backend.social;

public final class SavedPhotoResponses {
    private SavedPhotoResponses() {
    }

    public record State(long photo_id, boolean saved_by_viewer) {
    }
}
