package com.stog.backend.social;

import com.stog.backend.plan.TripMembershipPolicy;
import com.stog.backend.storage.PhotoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhotoLikeService {
    private final PhotoRepository photos;
    private final PhotoLikeRepository likes;
    private final TripMembershipPolicy memberships;

    public PhotoLikeService(
        PhotoRepository photos,
        PhotoLikeRepository likes,
        TripMembershipPolicy memberships
    ) {
        this.photos = photos;
        this.likes = likes;
        this.memberships = memberships;
    }

    @Transactional
    public PhotoLikeResponses.State like(long userId, long photoId) {
        memberships.requireKnownUser(userId);
        PhotoRepository.StoredPhoto photo = requireEligiblePhoto(photoId);
        if (!likes.insert(userId, photo.id())) {
            return new PhotoLikeResponses.State(
                photo.id(),
                photos.likeCount(photo.id()),
                true
            );
        }
        long likeCount = photos.incrementLikeCount(photo.id());
        photos.refreshCellProjection(photo.cellId());
        return new PhotoLikeResponses.State(photo.id(), likeCount, true);
    }

    @Transactional
    public PhotoLikeResponses.State unlike(long userId, long photoId) {
        memberships.requireKnownUser(userId);
        PhotoRepository.StoredPhoto photo = requireEligiblePhoto(photoId);
        if (!likes.delete(userId, photo.id())) {
            return new PhotoLikeResponses.State(
                photo.id(),
                photos.likeCount(photo.id()),
                false
            );
        }
        long likeCount = photos.decrementLikeCount(photo.id());
        photos.refreshCellProjection(photo.cellId());
        return new PhotoLikeResponses.State(photo.id(), likeCount, false);
    }

    private PhotoRepository.StoredPhoto requireEligiblePhoto(long photoId) {
        return photos.lockPubliclyEligible(photoId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Eligible public photo not found")
        );
    }
}
