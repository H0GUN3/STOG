package com.stog.backend.storage;

import jakarta.validation.Valid;
import com.stog.backend.plan.CurrentUserId;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("gcs-write")
@RequestMapping("/photos")
public class PhotoController {
    private final PhotoService photos;

    public PhotoController(PhotoService photos) {
        this.photos = photos;
    }

    @PostMapping("/upload-url")
    public PhotoResponses.UploadUrl uploadUrl(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PhotoRequests.UploadUrl request
    ) {
        return photos.issueUploadUrl(CurrentUserId.from(jwt), request);
    }

    @PostMapping("/place-preview")
    public PhotoResponses.PlacePreview placePreview(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PhotoRequests.PlacePreview request
    ) {
        CurrentUserId.from(jwt);
        return photos.placePreview(request);
    }

    @PostMapping
    public PhotoResponses.Detail create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PhotoRequests.Create request
    ) {
        return photos.create(CurrentUserId.from(jwt), request);
    }

    @PatchMapping("/{id}/visibility")
    public PhotoResponses.Detail changeVisibility(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody PhotoRequests.Visibility request
    ) {
        return photos.changeVisibility(CurrentUserId.from(jwt), id, request);
    }

}
