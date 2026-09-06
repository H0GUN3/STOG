package com.stog.backend.social;

import com.stog.backend.plan.TripMembershipPolicy;
import com.stog.backend.storage.PhotoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SavedPhotoService {
    private final PhotoRepository photos;
    private final SavedPhotoRepository savedPhotos;
    private final TripMembershipPolicy memberships;

    public SavedPhotoService(
        PhotoRepository photos,
        SavedPhotoRepository savedPhotos,
        TripMembershipPolicy memberships
    ) {
        this.photos = photos;
        this.savedPhotos = savedPhotos;
        this.memberships = memberships;
    }

    @Transactional
    public SavedPhotoResponses.State save(long userId, long photoId) {
        memberships.requireKnownUser(userId);
        long eligiblePhotoId = requireFeedEligiblePhoto(photoId);
        savedPhotos.insert(userId, eligiblePhotoId);
        return new SavedPhotoResponses.State(eligiblePhotoId, true);
    }

    @Transactional
    public SavedPhotoResponses.State unsave(long userId, long photoId) {
        memberships.requireKnownUser(userId);
        long eligiblePhotoId = requireFeedEligiblePhoto(photoId);
        savedPhotos.delete(userId, eligiblePhotoId);
        return new SavedPhotoResponses.State(eligiblePhotoId, false);
    }

    private long requireFeedEligiblePhoto(long photoId) {
        return photos.lockFeedEligible(photoId)
            .map(PhotoRepository.StoredPhoto::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Eligible public feed photo not found"
            ));
    }
}
