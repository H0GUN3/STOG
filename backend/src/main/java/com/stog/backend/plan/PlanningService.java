package com.stog.backend.plan;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.google.GoogleProviderException;
import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanningService {
    private static final Set<String> JEONBUK_REGION_CODES = Set.of(
        "JEONBUK", "JEONJU", "GUNSAN", "IKSAN", "JEONGEUP", "NAMWON", "GIMJE",
        "WANJU", "JINAN", "MUJU", "JANGSU", "IMSIL", "SUNCHANG", "GOCHANG", "BUAN"
    );
    private final TripRepository trips;
    private final TripMembershipPolicy memberships;
    private final PlaceRepository places;
    private final BasketItemRepository basketItems;
    private final GooglePlacesClient googlePlaces;
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    @Autowired
    public PlanningService(
        TripRepository trips,
        TripMembershipPolicy memberships,
        PlaceRepository places,
        BasketItemRepository basketItems,
        GooglePlacesClient googlePlaces,
        ObjectProvider<GcsReadUrlSigner> signedReads
    ) {
        this.trips = trips;
        this.memberships = memberships;
        this.places = places;
        this.basketItems = basketItems;
        this.googlePlaces = googlePlaces;
        this.signedReads = signedReads;
    }

    PlanningService(
        TripRepository trips,
        TripMembershipPolicy memberships,
        PlaceRepository places,
        BasketItemRepository basketItems,
        GooglePlacesClient googlePlaces
    ) {
        this.trips = trips;
        this.memberships = memberships;
        this.places = places;
        this.basketItems = basketItems;
        this.googlePlaces = googlePlaces;
        this.signedReads = null;
    }

    @Transactional
    public TripResponses.Created createTrip(
        long ownerId,
        TripRequests.Create request
    ) {
        validateDateRange(request.planned_start_date(), request.planned_end_date());
        if (!JEONBUK_REGION_CODES.contains(
            request.region_code() == null ? "JEONBUK" : request.region_code()
        )) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown trip region");
        }
        return createdResponse(trips.create(ownerId, request));
    }

    @Transactional
    public TripResponses.Created updateTrip(
        long ownerId,
        long tripId,
        TripRequests.Update request
    ) {
        memberships.requireOwner(ownerId, tripId);
        validateDateRange(request.planned_start_date(), request.planned_end_date());
        TripRepository.TripRecord trip = trips.update(ownerId, tripId, request).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found")
        );
        return createdResponse(trip);
    }

    @Transactional(readOnly = true)
    public TripResponses.Created getTrip(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        TripRepository.TripRecord trip = trips.findForMember(userId, tripId).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found")
        );
        return createdResponse(trip);
    }

    @Transactional(readOnly = true)
    public List<TripResponses.Summary> listTrips(long ownerId) {
        return trips.findMine(ownerId).stream()
            .map(this::summaryResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public TripResponses.Home home(long userId) {
        TripRepository.HomeData home = trips.findHome(userId);
        return new TripResponses.Home(
            home.monthlyTripCount(),
            home.visitedCellCount(),
            home.savedPlaceCount(),
            home.monthlyReceivedLikeCount(),
            home.trips().stream().map(this::summaryResponse).toList()
        );
    }

    private TripResponses.Created createdResponse(TripRepository.TripRecord trip) {
        return new TripResponses.Created(
            trip.id(),
            trip.title(),
            trip.activityType(),
            trip.mode(),
            trip.visibility(),
            trip.plannedStartDate(),
            trip.plannedEndDate(),
            coverImageUrl(trip.coverImageKey()),
            trip.regionCode()
        );
    }

    private TripResponses.Summary summaryResponse(TripRepository.TripRecord trip) {
        return new TripResponses.Summary(
            trip.id(),
            trip.title(),
            trip.activityType(),
            trip.mode(),
            trip.visibility(),
            trip.plannedStartDate(),
            trip.plannedEndDate(),
            coverImageUrl(trip.coverImageKey()),
            trip.regionCode()
        );
    }

    private URI coverImageUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || signedReads == null) {
            return null;
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        return signer == null ? null : signer.issueReadUrl(objectKey);
    }

    private void validateDateRange(
        java.time.LocalDate startDate,
        java.time.LocalDate endDate
    ) {
        boolean hasStart = startDate != null;
        boolean hasEnd = endDate != null;
        if (hasStart != hasEnd || (hasStart && endDate.isBefore(startDate))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A trip requires a complete, ordered planned date range"
            );
        }
    }

    @Transactional(readOnly = true)
    public TripResponses.Archive archive(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        TripResponses.Archive archive = trips.findArchive(tripId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
        if (!"ended".equals(archive.mode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trip is not ended");
        }
        return archive;
    }

    @Transactional
    public BasketResponses.Added addPlace(
        long userId,
        BasketRequests.AddPlace request
    ) {
        memberships.requireActiveMember(userId, request.trip_id());
        requireFingerprint(request.payload_fingerprint(), BasketPayloadFingerprint.forPlace(request));
        basketItems.lockIdempotencyScope(userId, request.trip_id(), request.client_item_id());
        BasketResponses.Added replay = basketItems.findByClientItemId(
            userId, request.trip_id(), request.client_item_id()
        ).map(item -> replayPlace(item, request.payload_fingerprint())).orElse(null);
        if (replay != null) {
            return replay;
        }
        if (!"google".equals(request.provider()) && !"canonical".equals(request.provider())) {
            throw new IllegalArgumentException("Unsupported place provider");
        }
        BasketRequests.AddPlace canonicalRequest;
        PlaceRepository.Upserted place;
        if ("canonical".equals(request.provider())) {
            if (request.canonical_place_id() == null || request.canonical_source_id() == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Canonical place provenance is required"
                );
            }
            PlaceRepository.CanonicalReference reference = places.requireCanonical(
                request.canonical_place_id(),
                request.canonical_source_id(),
                request.external_id()
            );
            canonicalRequest = canonicalCatalogRequest(request.trip_id(), reference, request);
            place = reference.place();
        } else {
            if (request.canonical_place_id() != null || request.canonical_source_id() != null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provider place cannot include canonical provenance"
                );
            }
            canonicalRequest = canonicalGoogleRequest(request);
            place = places.upsertPrivateReference(canonicalRequest);
        }
        long basketItemId = basketItems.addPlace(
            userId,
            canonicalRequest.trip_id(),
            place,
            canonicalRequest
        );
        return new BasketResponses.Added(
            basketItemId,
            place.id(),
            place.cellId() == null ? null : CellIdCalculator.toWire(place.cellId()),
            "resolved"
        );
    }

    @Transactional
    public BasketResponses.LinkAdded addLink(
        long userId,
        BasketRequests.AddLink request
    ) {
        memberships.requireActiveMember(userId, request.trip_id());
        requireFingerprint(request.payload_fingerprint(), BasketPayloadFingerprint.forLink(request));
        basketItems.lockIdempotencyScope(userId, request.trip_id(), request.client_item_id());
        BasketItemRepository.IdempotentItem replay = basketItems.findByClientItemId(
            userId, request.trip_id(), request.client_item_id()
        ).orElse(null);
        if (replay != null) {
            requireFingerprint(request.payload_fingerprint(), replay.payloadFingerprint());
            return new BasketResponses.LinkAdded(replay.id(), replay.status());
        }
        long basketItemId = basketItems.addLink(userId, request);
        return new BasketResponses.LinkAdded(basketItemId, "unresolved");
    }

    @Transactional(readOnly = true)
    public List<BasketResponses.Item> listBasket(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        return basketItems.findByTrip(tripId);
    }

    @Transactional
    public void removeBasketItem(long userId, long tripId, long basketItemId) {
        memberships.requireActiveMember(userId, tripId);
        if (!basketItems.existsInTrip(tripId, basketItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket item not found");
        }
        if (basketItems.isFixedInItinerary(tripId, basketItemId)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Fixed itinerary item cannot be removed"
            );
        }
        if (!basketItems.deleteFromTrip(tripId, basketItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Basket item not found");
        }
    }

    private BasketRequests.AddPlace canonicalCatalogRequest(
        long tripId,
        PlaceRepository.CanonicalReference reference,
        BasketRequests.AddPlace referenceRequest
    ) {
        return new BasketRequests.AddPlace(
            tripId,
            referenceRequest.client_item_id(),
            referenceRequest.payload_fingerprint(),
            reference.source(),
            reference.externalId(),
            reference.name(),
            reference.category(),
            reference.latitude(),
            reference.longitude(),
            reference.id(),
            reference.sourceRecordId()
        );
    }

    private BasketResponses.Added replayPlace(
        BasketItemRepository.IdempotentItem item,
        String payloadFingerprint
    ) {
        requireFingerprint(payloadFingerprint, item.payloadFingerprint());
        if (item.placeId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client item key belongs to another item type");
        }
        return new BasketResponses.Added(
            item.id(),
            item.placeId(),
            item.cellId() == null ? null : CellIdCalculator.toWire(item.cellId()),
            item.status()
        );
    }

    private void requireFingerprint(String supplied, String expected) {
        if (!expected.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Basket item payload conflicts with its immutable key");
        }
    }

    private BasketRequests.AddPlace canonicalGoogleRequest(
        BasketRequests.AddPlace request
    ) {
        PlaceCandidate candidate = googlePlaces.details(request.external_id());
        if (!"google".equals(candidate.provider())
            || !request.external_id().equals(candidate.external_id())
            || candidate.name() == null
            || candidate.name().isBlank()
            || candidate.latitude() == null
            || candidate.longitude() == null
            || !Double.isFinite(candidate.latitude())
            || candidate.latitude() < -90.0
            || candidate.latitude() > 90.0
            || !Double.isFinite(candidate.longitude())
            || candidate.longitude() < -180.0
            || candidate.longitude() > 180.0) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                "GOOGLE_PLACES_DETAILS_INVALID_RESPONSE",
                false
            );
        }
        return new BasketRequests.AddPlace(
            request.trip_id(),
            request.client_item_id(),
            request.payload_fingerprint(),
            "google",
            candidate.external_id(),
            candidate.name(),
            candidate.types().stream()
                .filter(type -> !type.isBlank())
                .findFirst()
                .orElse("place"),
            candidate.latitude(),
            candidate.longitude(),
            null,
            null
        );
    }
}
