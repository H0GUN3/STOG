package com.stog.backend.storage;

import com.stog.backend.plan.CurrentUserId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"gcs-read", "gcs-write"})
@RequestMapping("/trips")
public class TripPhotoController {
    private final PhotoReadService photos;

    public TripPhotoController(PhotoReadService photos) {
        this.photos = photos;
    }

    @GetMapping("/{tripId}/photos")
    public List<PhotoResponses.ArchiveItem> archive(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return photos.archive(CurrentUserId.from(jwt), tripId);
    }
}
