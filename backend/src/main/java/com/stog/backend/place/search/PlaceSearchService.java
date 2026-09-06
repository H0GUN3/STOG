package com.stog.backend.place.search;

import com.stog.backend.google.GoogleProviderException;
import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import com.stog.backend.place.PlaceRequests;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlaceSearchService {
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);
    private static final Set<String> TOURISM_TYPES = Set.of(
        "tourist_attraction",
        "museum",
        "park",
        "art_gallery",
        "national_park",
        "state_park",
        "historical_landmark",
        "zoo",
        "aquarium",
        "amusement_park"
    );
    private final PlaceSearchNormalizer normalizer;
    private final PlaceSearchRepository repository;
    private final GooglePlacesClient googlePlaces;
    private final PlaceSearchProperties properties;
    private final NearbyPlaceSelector nearbyPlaceSelector;

    public PlaceSearchService(
        PlaceSearchNormalizer normalizer,
        PlaceSearchRepository repository,
        GooglePlacesClient googlePlaces,
        PlaceSearchProperties properties,
        NearbyPlaceSelector nearbyPlaceSelector
    ) {
        this.normalizer = normalizer;
        this.repository = repository;
        this.googlePlaces = googlePlaces;
        this.properties = properties;
        this.nearbyPlaceSelector = nearbyPlaceSelector;
    }

    @Transactional(readOnly = true)
    public PlaceSearchResponse nearby(PlaceRequests.Nearby request) {
        PlaceRequests.Nearby tourismRequest = validatedTourismRequest(request);
        if (!validLatitude(request.center().latitude())
            || !validLongitude(request.center().longitude())
            || !Double.isFinite(request.radius_meters())
            || request.radius_meters() <= 0.0) {
            throw badRequest("nearby coordinates or radius are invalid");
        }
        int resultLimit = request.max_result_count() == null
            ? properties.resultLimit()
            : request.max_result_count();
        if (resultLimit < 1 || resultLimit > properties.resultLimit()) {
            throw badRequest("max_result_count exceeds the catalog search result limit");
        }

        List<PlaceSearchResult> localResults = new ArrayList<>(repository.nearby(
            request.center().latitude(),
            request.center().longitude(),
            request.radius_meters(),
            resultLimit
        ).stream().map(PlaceSearchRepository.LocalResult::toSearchResult).toList());

        List<PlaceSearchResult> providerResults = nearbyProviderResults(
            tourismRequest,
            resultLimit,
            localResults
        );
        return new PlaceSearchResponse(nearbyPlaceSelector.merge(
            localResults,
            providerResults,
            resultLimit
        ));
    }

    @Transactional(readOnly = true)
    public PlaceSearchResponse search(PlaceRequests.Search request) {
        PlaceSearchNormalizer.Normalized query = normalizer.normalize(request.query());
        if (query.normalized_name().isBlank()) {
            throw badRequest("query is blank after normalization");
        }

        PlaceRequests.Search providerRequest = validatedProviderRequest(request);
        int resultLimit = providerRequest.max_result_count();
        List<PlaceSearchResult> results = new ArrayList<>(repository.search(
            query,
            resultLimit,
            properties.fuzzyThreshold()
        ).stream().map(PlaceSearchRepository.LocalResult::toSearchResult).toList());
        int localResultGate = Math.min(properties.fallbackMinResults(), resultLimit);
        if (results.size() == resultLimit
            || (results.size() >= localResultGate && !containsGeneralPlace(results))) {
            return new PlaceSearchResponse(results);
        }

        List<PlaceCandidate> providerCandidates = googlePlaces.textSearch(
            providerRequestForRemainingCapacity(providerRequest, results.size())
        );
        for (PlaceCandidate candidate : providerCandidates) {
            if (results.size() == resultLimit) {
                break;
            }
            results.add(PlaceSearchResult.provider(candidate));
        }
        return new PlaceSearchResponse(results);
    }

    private boolean containsGeneralPlace(List<PlaceSearchResult> results) {
        return results.stream().anyMatch(result -> result.types().stream().anyMatch(type ->
            switch (type) {
                case "cafe", "restaurant", "lodging", "shopping", "transit_station",
                    "service", "place" -> true;
                default -> false;
            }
        ));
    }

    private List<PlaceSearchResult> nearbyProviderResults(
        PlaceRequests.Nearby request,
        int resultLimit,
        List<PlaceSearchResult> localResults
    ) {
        if (!nearbyPlaceSelector.needsProvider(localResults, resultLimit)) {
            return List.of();
        }
        try {
            return googlePlaces.nearbySearch(
                new PlaceRequests.Nearby(
                    request.center(),
                    request.radius_meters(),
                    request.included_types(),
                    properties.resultLimit()
                )
            ).stream()
                .map(PlaceSearchResult::provider)
                .filter(this::isTourismResult)
                .toList();
        } catch (GoogleProviderException error) {
            if (localResults.isEmpty()) {
                throw error;
            }
            log.warn(
                "Nearby provider unavailable; returning canonical candidates: {}",
                error.code()
            );
            return List.of();
        }
    }

    private PlaceRequests.Nearby validatedTourismRequest(PlaceRequests.Nearby request) {
        List<String> includedTypes = request.included_types().stream()
            .filter(Objects::nonNull)
            .map(type -> type.trim().toLowerCase(Locale.ROOT))
            .filter(TOURISM_TYPES::contains)
            .distinct()
            .toList();
        if (includedTypes.isEmpty()) {
            throw badRequest("nearby included_types must contain tourism types");
        }
        return new PlaceRequests.Nearby(
            request.center(),
            request.radius_meters(),
            includedTypes,
            request.max_result_count()
        );
    }

    private boolean isTourismResult(PlaceSearchResult result) {
        return result.types().stream()
            .map(type -> type.trim().toLowerCase(Locale.ROOT))
            .anyMatch(TOURISM_TYPES::contains);
    }

    private PlaceRequests.Search validatedProviderRequest(PlaceRequests.Search request) {
        int resultLimit = request.max_result_count() == null
            ? properties.resultLimit()
            : request.max_result_count();
        if (resultLimit < 1 || resultLimit > properties.resultLimit()) {
            throw badRequest("max_result_count exceeds the catalog search result limit");
        }

        boolean anyCoordinates = request.latitude() != null || request.longitude() != null;
        if (anyCoordinates && (request.latitude() == null || request.longitude() == null)) {
            throw badRequest("latitude and longitude must be provided together");
        }
        if (!anyCoordinates && request.radius_meters() != null) {
            throw badRequest("radius_meters requires latitude and longitude");
        }
        if (!anyCoordinates) {
            return new PlaceRequests.Search(
                request.query(),
                null,
                null,
                null,
                resultLimit
            );
        }
        if (!validLatitude(request.latitude()) || !validLongitude(request.longitude())) {
            throw badRequest("latitude and longitude are invalid");
        }
        double radius = request.radius_meters() == null
            ? properties.defaultRadiusMeters()
            : request.radius_meters();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            throw badRequest("radius_meters is invalid");
        }
        return new PlaceRequests.Search(
            request.query(),
            request.latitude(),
            request.longitude(),
            radius,
            resultLimit
        );
    }

    private PlaceRequests.Search providerRequestForRemainingCapacity(
        PlaceRequests.Search request,
        int localResultCount
    ) {
        return new PlaceRequests.Search(
            request.query(),
            request.latitude(),
            request.longitude(),
            request.radius_meters(),
            request.max_result_count() - localResultCount
        );
    }

    private boolean validLatitude(Double value) {
        return value != null
            && Double.isFinite(value)
            && value >= -90.0
            && value <= 90.0;
    }

    private boolean validLongitude(Double value) {
        return value != null
            && Double.isFinite(value)
            && value >= -180.0
            && value <= 180.0;
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
