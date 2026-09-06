package com.stog.backend.storage;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.plan.EffectiveVisibilityService;
import com.stog.backend.plan.TripMembershipPolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("gcs-write")
public class PhotoService {
    private static final String[] SOURCES = {"camera", "gallery"};
    private static final String[] VISIBILITIES = {"private", "group", "public"};

    private final PhotoRepository photos;
    private final GcsSignedUrlService storage;
    private final TripMembershipPolicy memberships;
    private final EffectiveVisibilityService visibility;
    private final PhotoPolicyService policy;
    private final SetLogProperties setLog;
    private final PhotoPlaceResolver placeResolver;

    @Autowired
    public PhotoService(
        PhotoRepository photos,
        GcsSignedUrlService storage,
        TripMembershipPolicy memberships,
        EffectiveVisibilityService visibility,
        PhotoPolicyService policy,
        SetLogProperties setLog,
        PhotoPlaceResolver placeResolver
    ) {
        this.photos = photos;
        this.storage = storage;
        this.memberships = memberships;
        this.visibility = visibility;
        this.policy = policy;
        this.setLog = setLog;
        this.placeResolver = placeResolver;
    }

    PhotoService(
        PhotoRepository photos,
        GcsSignedUrlService storage,
        TripMembershipPolicy memberships,
        EffectiveVisibilityService visibility,
        PhotoPolicyService policy
    ) {
        this(
            photos, storage, memberships, visibility, policy,
            new SetLogProperties(120, 100, 75, 10), null
        );
    }

    PhotoService(
        PhotoRepository photos,
        GcsSignedUrlService storage,
        TripMembershipPolicy memberships,
        EffectiveVisibilityService visibility,
        PhotoPolicyService policy,
        PhotoPlaceResolver placeResolver
    ) {
        this(
            photos, storage, memberships, visibility, policy,
            new SetLogProperties(120, 100, 75, 10), placeResolver
        );
    }

    public PhotoResponses.PlacePreview placePreview(PhotoRequests.PlacePreview request) {
        if (request == null || request.latitude() == null || request.longitude() == null) {
            throw invalid("latitude and longitude are required");
        }
        PhotoPlaceResolver.Resolution resolution = requirePlaceResolver().resolve(
            request.latitude(), request.longitude(), request.accuracy_m()
        );
        return new PhotoResponses.PlacePreview(
            resolution.status(), resolution.placeId(), resolution.placeName()
        );
    }

    @Transactional
    public PhotoResponses.UploadUrl issueUploadUrl(
        long userId,
        PhotoRequests.UploadUrl request
    ) {
        if (request == null || request.trip_id() == null) {
            throw invalid("trip_id is required");
        }
        requireActiveMember(userId, request.trip_id());
        try {
            GcsSignedUrlService.PreparedUpload prepared = storage.prepareUpload(
                Long.toString(userId),
                request
            );
            return storage.issueUploadUrl(prepared);
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        }
    }

    @Transactional
    public PhotoResponses.Detail create(long userId, PhotoRequests.Create request) {
        validateReplayIdentity(request);
        String finalizeFingerprint = PhotoFinalizeFingerprint.finalizeRequest(userId, request);
        photos.lockFinalizeIdentity(userId, request.client_upload_id());
        PhotoRepository.FinalizedPhoto committed = photos.findFinalized(
            userId,
            request.client_upload_id()
        ).orElse(null);
        if (committed != null) {
            return committedReplay(committed, finalizeFingerprint);
        }

        validateCreateRequest(request);
        requireActiveMember(userId, request.trip_id());
        PhotoRequests.Create effectiveRequest = publicTripRequest(request);
        verifyUploadedObjects(userId, effectiveRequest);
        PhotoPlaceResolver.Resolution place = resolveFinalPlace(effectiveRequest);
        Long cellId = effectiveRequest.latitude() == null
            ? null
            : calculateCell(effectiveRequest.latitude(), effectiveRequest.longitude());
        String publicationStatus = publicationStatus(effectiveRequest);
        PhotoRepository.StoredPhoto inserted = photos.createIfAbsent(
            userId,
            effectiveRequest,
            cellId,
            finalizeFingerprint,
            place,
            publicationStatus
        ).orElse(null);
        PhotoRepository.FinalizedPhoto finalized = inserted == null
            ? photos.findFinalized(userId, request.client_upload_id())
                .orElseThrow(() -> conflict("Photo object keys already finalized"))
            : new PhotoRepository.FinalizedPhoto(
                inserted,
                request.trip_id(),
                request.client_upload_id(),
                finalizeFingerprint,
                inserted.visibility(),
                "pending",
                inserted.placeId(),
                inserted.placeNameSnapshot(),
                inserted.placeResolutionStatus(),
                inserted.publicationStatus()
            );
        if (inserted != null && Boolean.TRUE.equals(effectiveRequest.public_consent())) {
            policy.acceptPublicGrant(userId, inserted.id());
        }
        return committedReplay(finalized, finalizeFingerprint);
    }

    @Transactional(readOnly = true)
    public PhotoResponses.Detail get(long userId, long photoId) {
        requireReadable(userId, photoId);
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

    @Transactional
    public PhotoResponses.Detail changeVisibility(
        long userId,
        long photoId,
        PhotoRequests.Visibility request
    ) {
        if (request == null || !contains(VISIBILITIES, request.visibility())) {
            throw invalid("visibility is unsupported");
        }
        PhotoRepository.StoredPhoto photo = requirePhotoOwner(userId, photoId);
        PhotoRepository.StoredPhoto updated = photos.updateVisibility(
            photo.id(),
            request.visibility()
        );
        photos.refreshCellProjection(updated.cellId());
        return detail(updated);
    }

    public PhotoResponses.PublicGrant acceptPublicGrant(long userId, long photoId) {
        return policy.acceptPublicGrant(userId, photoId);
    }

    public PhotoResponses.PublicGrant revokePublicGrant(
        long userId,
        long photoId,
        int version
    ) {
        return policy.revokePublicGrant(userId, photoId, version);
    }

    public List<PhotoResponses.PublicGrant> grants(long userId, long photoId) {
        return policy.grants(userId, photoId);
    }

    public PhotoResponses.Moderation moderate(
        long moderatorId,
        long photoId,
        PhotoRequests.Moderation request
    ) {
        return policy.moderate(moderatorId, photoId, request);
    }

    private PhotoRepository.StoredPhoto requirePhotoOwner(long userId, long photoId) {
        PhotoRepository.StoredPhoto photo = photos.lock(photoId)
            .orElseThrow(() -> notFound(photoId));
        if (photo.userId() != userId) {
            throw forbidden("Photo owner access denied");
        }
        return photo;
    }

    private void requireReadable(long userId, long photoId) {
        if (!visibility.canReadPhoto(userId, photoId)) {
            throw forbidden("Photo access denied");
        }
    }

    private void validateCreateRequest(PhotoRequests.Create request) {
        if (request == null || !contains(SOURCES, request.source())) {
            throw invalid("source is unsupported");
        }
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw invalid("latitude and longitude must be provided together");
        }
        if (request.latitude() != null) {
            calculateCell(request.latitude(), request.longitude());
        }
        if (request.accuracy_m() != null
            && (!Double.isFinite(request.accuracy_m())
                || request.accuracy_m() < 0
                || request.accuracy_m() > setLog.locationMaxAccuracyMeters())) {
            throw invalid("accuracy_m is invalid");
        }
        if ((request.accuracy_m() != null || request.location_provenance() != null)
            && request.latitude() == null) {
            throw invalid("location metadata requires latitude and longitude");
        }
        if (request.location_provenance() != null
            && !request.location_provenance().equals("camera_foreground")
            && !request.location_provenance().equals("gallery_exif")) {
            throw invalid("location_provenance is unsupported");
        }
        if (request.caption() != null
            && (request.caption().contains("\r") || request.caption().contains("\n"))) {
            throw invalid("caption must be one line");
        }
        if (request.caption() != null
            && request.caption().codePointCount(0, request.caption().length())
                > setLog.captionMaxCodePoints()) {
            throw invalid("caption exceeds the Set Log limit");
        }
        validatePlaceExpectation(request);
        validateVisibilityIntent(request);
    }

    private void validatePlaceExpectation(PhotoRequests.Create request) {
        String status = request.place_resolution_status();
        if (status == null) {
            if (request.expected_place_id() != null) {
                throw invalid("expected_place_id requires place_resolution_status");
            }
            return;
        }
        if (!status.equals("matched") && !status.equals("no_match") && !status.equals("pending")) {
            throw invalid("place_resolution_status is unsupported");
        }
        if (status.equals("matched") != (request.expected_place_id() != null)) {
            throw invalid("place resolution expectation is inconsistent");
        }
        if (request.expected_place_id() != null && request.expected_place_id() <= 0) {
            throw invalid("expected_place_id is invalid");
        }
    }

    private void validateVisibilityIntent(PhotoRequests.Create request) {
        String requested = request.visibility() == null ? "private" : request.visibility();
        if (!requested.equals("private") && !requested.equals("public")) {
            throw invalid("visibility is unsupported for Set Log confirmation");
        }
    }

    private PhotoRequests.Create publicTripRequest(PhotoRequests.Create request) {
        if (!"public".equals(photos.tripVisibility(request.trip_id()))
            || "public".equals(request.visibility())) {
            return request;
        }
        return new PhotoRequests.Create(
            request.trip_id(),
            request.source(),
            request.client_upload_id(),
            request.original_key(),
            request.thumb_key(),
            request.original(),
            request.thumbnail(),
            request.latitude(),
            request.longitude(),
            request.accuracy_m(),
            request.location_provenance(),
            request.taken_at(),
            request.caption(),
            request.place_resolution_status(),
            request.expected_place_id(),
            "public",
            request.public_consent()
        );
    }

    private PhotoPlaceResolver.Resolution resolveFinalPlace(PhotoRequests.Create request) {
        if (request.place_resolution_status() == null) {
            return null;
        }
        PhotoPlaceResolver.Resolution resolved = request.latitude() == null
            ? new PhotoPlaceResolver.Resolution("no_match", null, null)
            : requirePlaceResolver().resolve(
                request.latitude(), request.longitude(), request.accuracy_m()
            );
        if ("matched".equals(request.place_resolution_status())
            && (!"matched".equals(resolved.status())
                || !request.expected_place_id().equals(resolved.placeId()))) {
            throw placeMismatch();
        }
        if ("no_match".equals(request.place_resolution_status())
            && !"no_match".equals(resolved.status())) {
            throw placeMismatch();
        }
        return resolved;
    }

    private String publicationStatus(PhotoRequests.Create request) {
        String requested = request.visibility() == null ? "private" : request.visibility();
        if (!"public".equals(requested)) {
            return "private";
        }
        return "public".equals(photos.tripVisibility(request.trip_id()))
            ? "public"
            : "trip_not_public";
    }

    private PhotoPlaceResolver requirePlaceResolver() {
        if (placeResolver == null) {
            throw new PhotoApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PHOTO_PLACE_RESOLUTION_FAILED",
                "Place resolution is temporarily unavailable"
            );
        }
        return placeResolver;
    }

    private void requireActiveMember(long userId, long tripId) {
        try {
            memberships.requireActiveMember(userId, tripId);
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                throw forbidden(error.getReason() == null ? "Trip access denied" : error.getReason());
            }
            throw error;
        }
    }

    private long calculateCell(double latitude, double longitude) {
        try {
            return CellIdCalculator.fromCoords(latitude, longitude);
        } catch (IllegalArgumentException error) {
            throw invalid("latitude and longitude are invalid");
        }
    }

    private void verifyUploadedObjects(long userId, PhotoRequests.Create request) {
        try {
            storage.verifyUploadedObjects(
                Long.toString(userId),
                request.uploadRequest(),
                request.original_key(),
                request.thumb_key()
            );
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        } catch (RuntimeException error) {
            throw new PhotoApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PHOTO_STORAGE_FAILED",
                "Photo storage is temporarily unavailable"
            );
        }
    }

    private void validateReplayIdentity(PhotoRequests.Create request) {
        if (request == null
            || request.trip_id() == null
            || request.trip_id() <= 0
            || request.client_upload_id() == null
            || request.original() == null
            || request.thumbnail() == null) {
            throw invalid("Photo finalization identity is invalid");
        }
    }

    private PhotoResponses.Detail committedReplay(
        PhotoRepository.FinalizedPhoto finalized,
        String requestedFingerprint
    ) {
        if (!finalized.finalizeFingerprint().equals(requestedFingerprint)) {
            throw conflict("Photo finalization conflicts with the committed response");
        }
        PhotoRepository.StoredPhoto photo = finalized.photo();
        return new PhotoResponses.Detail(
            photo.id(),
            finalized.finalizeTripId(),
            photo.userId(),
            photo.source(),
            photo.cellId() == null ? null : CellIdCalculator.toWire(photo.cellId()),
            photo.latitude(),
            photo.longitude(),
            photo.accuracyMeters(),
            photo.locationProvenance(),
            photo.takenAt(),
            null,
            null,
            photo.caption(),
            finalized.finalizePlaceId(),
            finalized.finalizePlaceNameSnapshot(),
            finalized.finalizePlaceResolutionStatus(),
            finalized.finalizeVisibility(),
            finalized.finalizeModerationStatus(),
            photo.publicConsent(),
            finalized.finalizePublicationStatus(),
            photo.createdAt()
        );
    }

    private PhotoResponses.Detail detail(PhotoRepository.StoredPhoto photo) {
        try {
            PhotoResponses.ReadUrls readUrls = storage.issueReadUrls(
                photo.originalKey(),
                photo.thumbKey()
            );
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
                readUrls.original_url(),
                readUrls.thumbnail_url(),
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
            URI thumbnailUrl = storage.issueReadUrl(photo.thumbKey());
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
        return new PhotoApiException(HttpStatus.BAD_REQUEST, "PHOTO_VALIDATION_FAILED", message);
    }

    private ResponseStatusException forbidden(String message) {
        return new PhotoApiException(HttpStatus.FORBIDDEN, "PHOTO_ACCESS_DENIED", message);
    }

    private ResponseStatusException conflict(String message) {
        return new PhotoApiException(HttpStatus.CONFLICT, "PHOTO_FINALIZE_CONFLICT", message);
    }

    private ResponseStatusException placeMismatch() {
        return new PhotoApiException(
            HttpStatus.CONFLICT,
            "PHOTO_PLACE_MISMATCH",
            "Place preview no longer matches finalization"
        );
    }

    private boolean contains(String[] values, String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
