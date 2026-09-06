package com.stog.backend.place.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class NearbyPlaceSelector {
    private final PlaceSearchNormalizer normalizer;
    private final PlaceSearchProperties properties;

    NearbyPlaceSelector(
        PlaceSearchNormalizer normalizer,
        PlaceSearchProperties properties
    ) {
        this.normalizer = normalizer;
        this.properties = properties;
    }

    boolean needsProvider(List<PlaceSearchResult> localResults, int resultLimit) {
        return localResults.size() < resultLimit
            || localResults.stream().noneMatch(this::hasPhotos);
    }

    List<PlaceSearchResult> merge(
        List<PlaceSearchResult> localResults,
        List<PlaceSearchResult> providerResults,
        int resultLimit
    ) {
        List<PlaceSearchResult> unique = new ArrayList<>();
        localResults.forEach(candidate -> add(unique, candidate));
        providerResults.forEach(candidate -> add(unique, candidate));
        return unique.stream()
            .sorted(Comparator.comparing(this::hasPhotos).reversed())
            .limit(resultLimit)
            .toList();
    }

    private void add(List<PlaceSearchResult> unique, PlaceSearchResult candidate) {
        int duplicateIndex = -1;
        for (int index = 0; index < unique.size(); index++) {
            if (samePlace(unique.get(index), candidate)) {
                duplicateIndex = index;
                break;
            }
        }
        if (duplicateIndex < 0) {
            unique.add(candidate);
        } else if (hasPhotos(candidate) && !hasPhotos(unique.get(duplicateIndex))) {
            unique.set(duplicateIndex, candidate);
        }
    }

    private boolean samePlace(PlaceSearchResult first, PlaceSearchResult second) {
        if (Objects.equals(first.provider(), second.provider())
            && first.external_id() != null
            && !first.external_id().isBlank()
            && first.external_id().equals(second.external_id())) {
            return true;
        }
        PlaceSearchNormalizer.Normalized firstName = normalizer.normalize(first.name());
        PlaceSearchNormalizer.Normalized secondName = normalizer.normalize(second.name());
        if (firstName.normalized_name().isBlank()
            || (!firstName.normalized_name().equals(secondName.normalized_name())
                && !firstName.compact_name().equals(secondName.compact_name()))) {
            return false;
        }
        if (first.latitude() == null || first.longitude() == null
            || second.latitude() == null || second.longitude() == null) {
            return true;
        }
        return distanceMeters(
            first.latitude(),
            first.longitude(),
            second.latitude(),
            second.longitude()
        ) <= properties.nearbyDuplicateDistanceMeters();
    }

    private boolean hasPhotos(PlaceSearchResult result) {
        return !result.photo_names().isEmpty() || !result.photo_urls().isEmpty();
    }

    private double distanceMeters(
        double firstLatitude,
        double firstLongitude,
        double secondLatitude,
        double secondLongitude
    ) {
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double haversine = Math.pow(Math.sin(latitudeDelta / 2.0), 2.0)
            + Math.cos(firstLatitudeRadians)
            * Math.cos(secondLatitudeRadians)
            * Math.pow(Math.sin(longitudeDelta / 2.0), 2.0);
        return 6371000.0 * 2.0 * Math.asin(Math.sqrt(Math.min(1.0, haversine)));
    }
}
