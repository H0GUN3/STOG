package com.stog.backend.storage;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhotoPolicyService {
    private static final String[] MODERATION_TARGETS = {"approved", "blocked"};

    private final PhotoRepository photos;

    public PhotoPolicyService(PhotoRepository photos) {
        this.photos = photos;
    }

    @Transactional
    public PhotoResponses.PublicGrant acceptPublicGrant(long userId, long photoId) {
        PhotoRepository.StoredPhoto photo = requirePhotoOwner(userId, photoId);
        if (photos.hasActiveGrant(photo.id())) {
            throw conflict("Public grant is already active");
        }
        PhotoResponses.PublicGrant grant = photos.createNextGrant(photo.id(), userId);
        photos.refreshCellProjection(photo.cellId());
        return grant;
    }

    @Transactional
    public PhotoResponses.PublicGrant revokePublicGrant(
        long userId,
        long photoId,
        int version
    ) {
        if (version < 1) {
            throw invalid("grant version is invalid");
        }
        PhotoRepository.StoredPhoto photo = requirePhotoOwner(userId, photoId);
        PhotoResponses.PublicGrant grant = photos.revokeGrant(photo.id(), version)
            .orElseThrow(() -> conflict("Public grant is not active"));
        photos.refreshCellProjection(photo.cellId());
        return grant;
    }

    @Transactional(readOnly = true)
    public List<PhotoResponses.PublicGrant> grants(long userId, long photoId) {
        PhotoRepository.StoredPhoto photo = photos.find(photoId)
            .orElseThrow(() -> notFound(photoId));
        if (photo.userId() != userId) {
            throw forbidden("Photo owner access denied");
        }
        return photos.findGrants(photoId);
    }

    @Transactional
    public PhotoResponses.Moderation moderate(
        long moderatorId,
        long photoId,
        PhotoRequests.Moderation request
    ) {
        if (!photos.isModerator(moderatorId)) {
            throw forbidden("Moderator access denied");
        }
        if (request == null || !contains(MODERATION_TARGETS, request.to_status())) {
            throw invalid("moderation target is unsupported");
        }
        String reason = validatedReason(request.reason());
        PhotoRepository.StoredPhoto photo = photos.lock(photoId)
            .orElseThrow(() -> notFound(photoId));
        if (!"pending".equals(photo.moderationStatus())) {
            throw conflict("Only pending photos can be moderated");
        }
        photos.updateModerationStatus(photo.id(), request.to_status());
        PhotoResponses.Moderation event = photos.appendModerationEvent(
            photo.id(),
            moderatorId,
            photo.moderationStatus(),
            request.to_status(),
            reason
        );
        photos.refreshCellProjection(photo.cellId());
        return event;
    }

    private PhotoRepository.StoredPhoto requirePhotoOwner(long userId, long photoId) {
        PhotoRepository.StoredPhoto photo = photos.lock(photoId)
            .orElseThrow(() -> notFound(photoId));
        if (photo.userId() != userId) {
            throw forbidden("Photo owner access denied");
        }
        return photo;
    }

    private String validatedReason(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.isBlank()) {
            throw invalid("moderation reason is blank");
        }
        return reason;
    }

    private boolean contains(String[] values, String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ResponseStatusException notFound(long photoId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found: " + photoId);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
