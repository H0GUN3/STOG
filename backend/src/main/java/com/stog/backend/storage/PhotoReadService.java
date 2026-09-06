package com.stog.backend.storage;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.plan.EffectiveVisibilityService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile({"gcs-read", "gcs-write"})
public class PhotoReadService {
    private final PhotoRepository photos;
    private final EffectiveVisibilityService visibility;
    private final GcsReadUrlSigner signedReads;

    public PhotoReadService(
        PhotoRepository photos,
        EffectiveVisibilityService visibility,
        GcsReadUrlSigner signedReads
    ) {
        this.photos = photos;
        this.visibility = visibility;
        this.signedReads = signedReads;
    }

    @Transactional(readOnly = true)
    public PhotoResponses.Detail get(long userId, long photoId) {
        if (!visibility.canReadPhoto(userId, photoId)) {
            throw forbidden("Photo access denied");
        }
        PhotoRepository.StoredPhoto photo = photos.find(photoId)
            .orElseThrow(() -> notFound(photoId));
        return detail(photo);
    }

    @Transactional(readOnly = true)
    public List<PhotoResponses.ArchiveItem> archive(long userId, long tripId) {
        if (!visibility.canReadTrip(userId, tripId)) {
            throw forbidden("Trip access denied");
        }
        List<PhotoResponses.ArchiveItem> archive = new ArrayList<>();
        for (PhotoRepository.StoredPhoto photo : photos.findByTrip(tripId)) {
            if (visibility.canReadPhoto(userId, photo.id())) {
                archive.add(archiveItem(photo));
            }
        }
        return List.copyOf(archive);
    }

    @Transactional(readOnly = true)
    public List<PhotoResponses.ArchiveItem> ownedArchive(long userId) {
        List<PhotoResponses.ArchiveItem> archive = new ArrayList<>();
        for (PhotoRepository.StoredPhoto photo : photos.findByUser(userId)) {
            if (visibility.canReadPhoto(userId, photo.id())) {
                archive.add(archiveItem(photo));
            }
        }
        return List.copyOf(archive);
    }

    private PhotoResponses.Detail detail(PhotoRepository.StoredPhoto photo) {
        try {
            URI originalUrl = signedReads.issueReadUrl(photo.originalKey());
            URI thumbnailUrl = signedReads.issueReadUrl(photo.thumbKey());
            return new PhotoResponses.Detail(
                photo.id(),
                photo.tripId(),
                photo.userId(),
                photo.source(),
                photo.cellId() == null ? null : CellIdCalculator.toWire(photo.cellId()),
                photo.latitude(),
                photo.longitude(),
                photo.accuracyMeters(),
                photo.locationProvenance(),
                photo.takenAt(),
                originalUrl,
                thumbnailUrl,
                photo.caption(),
                photo.placeId(),
                photo.placeNameSnapshot(),
                photo.placeResolutionStatus(),
                photo.visibility(),
                photo.moderationStatus(),
                photo.publicConsent(),
                photo.publicationStatus(),
                photo.createdAt()
            );
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        }
    }

    private PhotoResponses.ArchiveItem archiveItem(PhotoRepository.StoredPhoto photo) {
        try {
            URI thumbnailUrl = signedReads.issueReadUrl(photo.thumbKey());
            return new PhotoResponses.ArchiveItem(
                photo.id(),
                photo.tripId(),
                photo.userId(),
                photo.source(),
                photo.cellId() == null ? null : CellIdCalculator.toWire(photo.cellId()),
                photo.latitude(),
                photo.longitude(),
                photo.accuracyMeters(),
                photo.locationProvenance(),
                photo.takenAt(),
                thumbnailUrl,
                photo.caption(),
                photo.placeId(),
                photo.placeNameSnapshot(),
                photo.placeResolutionStatus(),
                photo.visibility(),
                photo.moderationStatus(),
                photo.publicConsent(),
                photo.publicationStatus(),
                photo.createdAt()
            );
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        }
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
}
