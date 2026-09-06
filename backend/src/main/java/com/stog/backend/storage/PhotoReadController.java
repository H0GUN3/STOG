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
@RequestMapping("/photos")
public class PhotoReadController {
    private final PhotoReadService photos;

    public PhotoReadController(PhotoReadService photos) {
        this.photos = photos;
    }

    @GetMapping("/{id}")
    public PhotoResponses.Detail get(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return photos.get(CurrentUserId.from(jwt), id);
    }

    @GetMapping("/mine")
    public List<PhotoResponses.ArchiveItem> mine(@AuthenticationPrincipal Jwt jwt) {
        return photos.ownedArchive(CurrentUserId.from(jwt));
    }
}
