package com.stog.backend.social;

import com.stog.backend.plan.ItineraryRepository;
import com.stog.backend.plan.TripMembershipPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicTripCopyService {
    private final PublicTripRepository trips;
    private final TripMembershipPolicy memberships;
    private final ItineraryRepository itineraries;

    public PublicTripCopyService(
        PublicTripRepository trips,
        TripMembershipPolicy memberships,
        ItineraryRepository itineraries
    ) {
        this.trips = trips;
        this.memberships = memberships;
        this.itineraries = itineraries;
    }

    @Transactional
    public PublicTripResponses.CopyResult copy(
        long userId,
        long sourceTripId,
        PublicTripRequests.Copy request
    ) {
        memberships.requireKnownUser(userId);
        String fingerprint = sha256(
            sourceTripId + "|" + request.destination_trip_id() + "|" + request.destination_day()
        );
        trips.lockCopyScope(userId, request.idempotency_key());
        PublicTripRepository.CopyReceipt existing = trips.findReceipt(
            userId, request.idempotency_key()
        ).orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new PublicTripApiException(
                    HttpStatus.CONFLICT,
                    "TRIP_COPY_IDEMPOTENCY_CONFLICT",
                    "idempotency_key was already used for another trip copy"
                );
            }
            return response(existing);
        }

        trips.lockEligible(sourceTripId);
        memberships.requireActiveMember(userId, request.destination_trip_id());
        int dayCount = itineraries.dayCount(request.destination_trip_id());
        if (request.destination_day() > dayCount) {
            throw new PublicTripApiException(
                HttpStatus.BAD_REQUEST,
                "TRIP_COPY_DESTINATION_DAY_INVALID",
                "destination_day is outside the destination trip"
            );
        }

        trips.lockDestinationItinerary(request.destination_trip_id());
        int order = trips.nextOrder(request.destination_trip_id(), request.destination_day());
        List<Long> basketIds = new ArrayList<>();
        List<Long> itineraryIds = new ArrayList<>();
        for (PublicTripRepository.SourceItem source : trips.sourceItems(sourceTripId)) {
            String basketFingerprint = sha256(fingerprint + "|" + source.basketItemId());
            long basketId = trips.copyBasketItem(
                userId,
                request.destination_trip_id(),
                source,
                "public-trip-copy-" + basketFingerprint,
                basketFingerprint
            );
            long itineraryId = trips.addItineraryItem(
                request.destination_trip_id(), basketId, request.destination_day(), order++, source
            );
            basketIds.add(basketId);
            itineraryIds.add(itineraryId);
        }
        long changeId = trips.recordChange(
            userId, sourceTripId, request.destination_trip_id(), request.destination_day(), basketIds.size()
        );
        PublicTripResponses.CopyResult result = new PublicTripResponses.CopyResult(
            sourceTripId,
            request.destination_trip_id(),
            request.destination_day(),
            basketIds.size(),
            basketIds,
            itineraryIds,
            changeId
        );
        trips.saveReceipt(userId, request.idempotency_key(), fingerprint, result);
        return result;
    }

    private static PublicTripResponses.CopyResult response(PublicTripRepository.CopyReceipt receipt) {
        return new PublicTripResponses.CopyResult(
            receipt.sourceTripId(), receipt.destinationTripId(), receipt.destinationDay(),
            receipt.copiedCount(), receipt.basketIds(), receipt.itineraryIds(), receipt.changeId()
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
