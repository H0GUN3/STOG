package com.stog.backend.plan;

import com.stog.backend.cell.CellIdCalculator;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VisitService {
    private final VisitRepository visits;
    private final TripRepository trips;
    private final TripMembershipPolicy memberships;
    private final VisitProperties properties;
    private final Clock clock;

    public VisitService(
        VisitRepository visits,
        TripRepository trips,
        TripMembershipPolicy memberships,
        VisitProperties properties,
        Clock clock
    ) {
        this.visits = visits;
        this.trips = trips;
        this.memberships = memberships;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public VisitResponses.Recorded record(
        long actorId,
        long tripId,
        VisitRequests.Record request
    ) {
        if (request.is_interpolated()) {
            throw badRequest("Interpolated segments cannot create visits or rewards");
        }
        long cellId = calculateMatchingCell(request);
        VisitDecision.Result decision = classify(request);
        if (!decision.status().value().equals(request.status())) {
            throw badRequest("Visit status does not match the observed interval");
        }
        String canonicalFingerprint = VisitPayloadFingerprint.compute(
            cellId,
            request,
            decision
        );
        if (!canonicalFingerprint.equals(request.payload_fingerprint())) {
            throw badRequest("payload_fingerprint does not match the canonical visit payload");
        }

        var existing = visits.find(actorId, tripId, request.client_visit_id());
        if (existing.isPresent()) {
            return requireIdentical(existing.get(), request.payload_fingerprint());
        }

        memberships.requireVisitMember(
            actorId,
            tripId,
            request.entered_at(),
            request.left_at()
        );
        TripRepository.VisitLifecycle lifecycle = trips.lockVisitLifecycle(tripId)
            .orElseThrow(() -> new VisitApiException(
                HttpStatus.FORBIDDEN,
                "VISIT_TRIP_ACCESS_DENIED",
                "Trip access denied"
            ));
        requireAcceptedLifecycle(lifecycle, request.left_at(), clock.instant());

        return visits.create(actorId, tripId, cellId, request, decision)
            .orElseGet(() -> requireIdentical(
                visits.find(actorId, tripId, request.client_visit_id())
                    .orElseThrow(() -> new IllegalStateException(
                        "Concurrent visit write completed without a stored response"
                    )),
                request.payload_fingerprint()
            ));
    }

    private VisitResponses.Recorded requireIdentical(
        VisitResponses.Recorded existing,
        String payloadFingerprint
    ) {
        if (!existing.payload_fingerprint().equals(payloadFingerprint)) {
            throw new VisitApiException(
                HttpStatus.CONFLICT,
                "VISIT_IDEMPOTENCY_CONFLICT",
                "client_visit_id was already committed with a different payload"
            );
        }
        return existing;
    }

    private VisitDecision.Result classify(VisitRequests.Record request) {
        try {
            return VisitDecision.classify(
                request.entered_at(),
                request.left_at(),
                false,
                properties.rules()
            );
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
    }

    private void requireAcceptedLifecycle(
        TripRepository.VisitLifecycle lifecycle,
        Instant leftAt,
        Instant receivedAt
    ) {
        if (!"ended".equals(lifecycle.mode())) {
            return;
        }
        if (lifecycle.endedAt() == null || leftAt.isAfter(lifecycle.endedAt())) {
            throw new VisitApiException(
                HttpStatus.CONFLICT,
                "VISIT_AFTER_TRIP_END",
                "The observed visit interval ended after the trip"
            );
        }
        if (receivedAt.isAfter(lifecycle.endedAt().plus(properties.lateGrace()))) {
            throw new VisitApiException(
                HttpStatus.CONFLICT,
                "VISIT_LATE_WINDOW_EXPIRED",
                "The late visit grace period has expired"
            );
        }
    }

    private long calculateMatchingCell(VisitRequests.Record request) {
        try {
            long calculated = CellIdCalculator.fromCoords(request.lat(), request.lng());
            if (!CellIdCalculator.toWire(calculated).equals(request.cell_id())) {
                throw badRequest("cell_id does not match latitude and longitude");
            }
            return calculated;
        } catch (IllegalArgumentException error) {
            throw badRequest("latitude and longitude are invalid");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
