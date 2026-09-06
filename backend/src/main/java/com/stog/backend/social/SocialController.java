package com.stog.backend.social;

import com.stog.backend.plan.CurrentUserId;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class SocialController {
    private final FeedService feed;
    private final PhotoLikeService likes;
    private final SavedPhotoService savedPhotos;
    private final PhotoCommentService comments;
    private final PublicTripFeedService publicTrips;
    private final PublicTripLikeService publicTripLikes;
    private final PublicTripCopyService publicTripCopies;

    public SocialController(
        FeedService feed,
        PhotoLikeService likes,
        SavedPhotoService savedPhotos,
        PhotoCommentService comments,
        PublicTripFeedService publicTrips,
        PublicTripLikeService publicTripLikes,
        PublicTripCopyService publicTripCopies
    ) {
        this.feed = feed;
        this.likes = likes;
        this.savedPhotos = savedPhotos;
        this.comments = comments;
        this.publicTrips = publicTrips;
        this.publicTripLikes = publicTripLikes;
        this.publicTripCopies = publicTripCopies;
    }

    @GetMapping("/feed")
    public FeedResponses.Page getFeed(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        Long viewerId = jwt == null ? null : CurrentUserId.from(jwt);
        return feed.get(viewerId, cursor, limit);
    }

    @GetMapping("/feed/saved")
    public FeedResponses.Page getSavedFeed(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        return feed.getSaved(CurrentUserId.from(jwt), cursor, limit);
    }

    @GetMapping("/public-trips")
    public PublicTripResponses.Page getPublicTrips(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        Long viewerId = jwt == null ? null : CurrentUserId.from(jwt);
        return publicTrips.get(viewerId, cursor, limit);
    }

    @PostMapping("/trips/{id}/like")
    public PublicTripResponses.LikeState likeTrip(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return publicTripLikes.like(CurrentUserId.from(jwt), id);
    }

    @DeleteMapping("/trips/{id}/like")
    public PublicTripResponses.LikeState unlikeTrip(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return publicTripLikes.unlike(CurrentUserId.from(jwt), id);
    }

    @PostMapping("/trips/{id}/copy")
    public PublicTripResponses.CopyResult copyTrip(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody PublicTripRequests.Copy request
    ) {
        return publicTripCopies.copy(CurrentUserId.from(jwt), id, request);
    }

    @PostMapping("/photos/{id}/save")
    public SavedPhotoResponses.State save(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return savedPhotos.save(CurrentUserId.from(jwt), id);
    }

    @DeleteMapping("/photos/{id}/save")
    public SavedPhotoResponses.State unsave(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return savedPhotos.unsave(CurrentUserId.from(jwt), id);
    }

    @GetMapping("/photos/{id}/comments")
    public PhotoCommentResponses.Page comments(@PathVariable long id) {
        return comments.list(id);
    }

    @PostMapping("/photos/{id}/comments")
    public PhotoCommentResponses.Comment comment(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody PhotoCommentRequests.Create request
    ) {
        return comments.create(CurrentUserId.from(jwt), id, request);
    }

    @PostMapping("/photos/{id}/like")
    public PhotoLikeResponses.State like(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return likes.like(CurrentUserId.from(jwt), id);
    }

    @DeleteMapping("/photos/{id}/like")
    public PhotoLikeResponses.State unlike(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id
    ) {
        return likes.unlike(CurrentUserId.from(jwt), id);
    }
}
