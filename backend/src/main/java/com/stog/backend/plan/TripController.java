package com.stog.backend.plan;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("!cloud")
@RequestMapping("/trips")
public class TripController {
    private final PlanningService planning;
    private final TripLifecycleService lifecycle;
    private final ItineraryService itineraries;
    private final ObjectProvider<TripCoverService> covers;

    public TripController(
        PlanningService planning,
        TripLifecycleService lifecycle,
        ItineraryService itineraries,
        ObjectProvider<TripCoverService> covers
    ) {
        this.planning = planning;
        this.lifecycle = lifecycle;
        this.itineraries = itineraries;
        this.covers = covers;
    }

    @PostMapping
    public TripResponses.Created create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody TripRequests.Create request
    ) {
        return planning.createTrip(CurrentUserId.from(jwt), request);
    }

    @PutMapping("/{tripId}")
    public TripResponses.Created update(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TripRequests.Update request
    ) {
        return planning.updateTrip(CurrentUserId.from(jwt), tripId, request);
    }

    @GetMapping("/{tripId}")
    public TripResponses.Created get(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return planning.getTrip(CurrentUserId.from(jwt), tripId);
    }

    @PatchMapping("/{tripId}/mode")
    public TripResponses.Mode updateMode(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TripRequests.Mode request
    ) {
        return lifecycle.update(CurrentUserId.from(jwt), tripId, request);
    }

    @GetMapping("/me")
    public List<TripResponses.Summary> mine(@AuthenticationPrincipal Jwt jwt) {
        return planning.listTrips(CurrentUserId.from(jwt));
    }

    @GetMapping("/me/home")
    public TripResponses.Home home(@AuthenticationPrincipal Jwt jwt) {
        return planning.home(CurrentUserId.from(jwt));
    }

    @PostMapping("/{tripId}/cover/upload-url")
    public TripCoverResponses.UploadUrl coverUploadUrl(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TripCoverRequests.Upload request
    ) {
        return requireCovers().issueUploadUrl(CurrentUserId.from(jwt), tripId, request);
    }

    @PostMapping("/{tripId}/cover/finalize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finalizeCover(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TripCoverRequests.Upload request
    ) {
        requireCovers().finalizeUpload(CurrentUserId.from(jwt), tripId, request);
    }

    @GetMapping("/{tripId}/archive")
    public TripResponses.Archive archive(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return planning.archive(CurrentUserId.from(jwt), tripId);
    }

    @GetMapping("/{tripId}/basket")
    public List<BasketResponses.Item> basket(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return planning.listBasket(CurrentUserId.from(jwt), tripId);
    }

    @DeleteMapping("/{tripId}/basket/{basketItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBasketItem(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @PathVariable long basketItemId
    ) {
        planning.removeBasketItem(CurrentUserId.from(jwt), tripId, basketItemId);
    }

    @GetMapping("/{tripId}/itinerary")
    public ItineraryResponses.Items itinerary(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return itineraries.get(CurrentUserId.from(jwt), tripId);
    }

    @GetMapping("/{tripId}/itinerary/changes")
    public ItineraryResponses.Changes itineraryChanges(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId
    ) {
        return itineraries.changes(CurrentUserId.from(jwt), tripId);
    }

    @PutMapping("/{tripId}/itinerary")
    public ItineraryResponses.Items replaceItinerary(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody ItineraryRequests.Replace request
    ) {
        return itineraries.replace(CurrentUserId.from(jwt), tripId, request);
    }

    private TripCoverService requireCovers() {
        TripCoverService service = covers.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Trip cover storage is not enabled"
            );
        }
        return service;
    }
}
