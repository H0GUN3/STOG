package com.stog.backend.plan;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EffectiveVisibilityService {
    private final EffectiveVisibilityRepository visibility;

    public EffectiveVisibilityService(EffectiveVisibilityRepository visibility) {
        this.visibility = visibility;
    }

    @Transactional(readOnly = true)
    public boolean canReadTrip(Long actorId, long tripId) {
        return visibility.findTrip(tripId)
            .map(trip -> EffectiveVisibility.canReadTrip(
                trip.visibility(),
                actorId != null && Objects.equals(trip.ownerId(), actorId),
                actorId != null && visibility.isActiveMember(actorId, tripId)
            ))
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canReadPhoto(Long actorId, long photoId) {
        return visibility.findPhoto(photoId)
            .map(photo -> canReadPhoto(actorId, photo))
            .orElse(false);
    }

    private boolean canReadPhoto(Long actorId, EffectiveVisibilityRepository.PhotoView photo) {
        boolean isOwner = actorId != null
            && Objects.equals(photo.photoOwnerId(), actorId);
        if (photo.tripId() == null) {
            return isOwner;
        }
        boolean isActiveMember = actorId != null
            && visibility.isActiveMember(actorId, photo.tripId());
        return EffectiveVisibility.canReadPhoto(
            photo.tripVisibility(),
            photo.photoVisibility(),
            isOwner,
            isActiveMember,
            photo.publiclyEligible()
        );
    }
}
