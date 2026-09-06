package com.stog.backend.plan;

import com.stog.backend.storage.GcsSignedUrlService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("gcs-write")
public class TripCoverService {
    private final TripMembershipPolicy memberships;
    private final TripRepository trips;
    private final GcsSignedUrlService storage;

    public TripCoverService(
        TripMembershipPolicy memberships,
        TripRepository trips,
        GcsSignedUrlService storage
    ) {
        this.memberships = memberships;
        this.trips = trips;
        this.storage = storage;
    }

    public TripCoverResponses.UploadUrl issueUploadUrl(
        long ownerId,
        long tripId,
        TripCoverRequests.Upload request
    ) {
        memberships.requireOwner(ownerId, tripId);
        try {
            GcsSignedUrlService.TripCoverImageUpload upload =
                storage.issueTripCoverImageUpload(
                    Long.toString(ownerId),
                    tripId,
                    request.client_upload_id(),
                    request.content_type(),
                    request.size_bytes(),
                    request.sha256()
                );
            return new TripCoverResponses.UploadUrl(
                upload.objectKey(),
                upload.uploadUrl(),
                upload.contentType(),
                upload.uploadHeaders(),
                upload.expiresAt()
            );
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    @Transactional
    public void finalizeUpload(
        long ownerId,
        long tripId,
        TripCoverRequests.Upload request
    ) {
        memberships.requireOwner(ownerId, tripId);
        try {
            String objectKey = storage.verifyTripCoverImageUpload(
                Long.toString(ownerId),
                tripId,
                request.client_upload_id(),
                request.content_type(),
                request.size_bytes(),
                request.sha256()
            );
            trips.setCoverImageKey(ownerId, tripId, objectKey);
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
