package com.stog.backend.storage;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PhotoPlaceResolver {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final PhotoPlaceRepository places;
    private final SetLogProperties properties;

    public PhotoPlaceResolver(PhotoPlaceRepository places, SetLogProperties properties) {
        this.places = places;
        this.properties = properties;
    }

    public Resolution resolve(double latitude, double longitude, Double accuracyMeters) {
        if (!validCoordinates(latitude, longitude) || invalidAccuracy(accuracyMeters)) {
            return Resolution.noMatch();
        }

        double latitudeDelta = Math.toDegrees(
            properties.placeRadiusMeters() / EARTH_RADIUS_METERS
        );
        double cosine = Math.cos(Math.toRadians(latitude));
        double longitudeDelta = Math.abs(cosine) < 1.0e-12
            ? 180.0
            : Math.min(180.0, latitudeDelta / Math.abs(cosine));
        double minLongitude = normalizeLongitude(longitude - longitudeDelta);
        double maxLongitude = normalizeLongitude(longitude + longitudeDelta);
        boolean crossesAntimeridian = minLongitude > maxLongitude;

        List<MeasuredCandidate> candidates;
        try {
            candidates = places.findEligibleCandidates(
                    Math.max(-90.0, latitude - latitudeDelta),
                    Math.min(90.0, latitude + latitudeDelta),
                    minLongitude,
                    maxLongitude,
                    crossesAntimeridian
                ).stream()
                .map(candidate -> new MeasuredCandidate(
                    candidate,
                    distanceMeters(latitude, longitude, candidate.latitude(), candidate.longitude())
                ))
                .filter(candidate -> Double.isFinite(candidate.distanceMeters()))
                .sorted(Comparator.comparingDouble(MeasuredCandidate::distanceMeters)
                    .thenComparingLong(candidate -> candidate.candidate().id()))
                .toList();
        } catch (PhotoApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new PhotoApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PHOTO_PLACE_RESOLUTION_FAILED",
                "Place resolution is temporarily unavailable"
            );
        }

        if (candidates.isEmpty()
            || candidates.get(0).distanceMeters() > properties.placeRadiusMeters()) {
            return Resolution.noMatch();
        }
        if (candidates.size() > 1
            && candidates.get(1).distanceMeters() - candidates.get(0).distanceMeters()
                <= properties.placeTieDeltaMeters()) {
            return Resolution.noMatch();
        }
        PhotoPlaceRepository.Candidate winner = candidates.get(0).candidate();
        if (winner.id() <= 0 || winner.name() == null || winner.name().isBlank()) {
            return Resolution.noMatch();
        }
        return new Resolution("matched", winner.id(), winner.name());
    }

    static double distanceMeters(
        double firstLatitude,
        double firstLongitude,
        double secondLatitude,
        double secondLongitude
    ) {
        if (!validCoordinates(firstLatitude, firstLongitude)
            || !validCoordinates(secondLatitude, secondLongitude)) {
            return Double.NaN;
        }
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double firstLatitudeRadians = Math.toRadians(firstLatitude);
        double secondLatitudeRadians = Math.toRadians(secondLatitude);
        double haversine = Math.sin(latitudeDelta / 2.0) * Math.sin(latitudeDelta / 2.0)
            + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
            * Math.sin(longitudeDelta / 2.0) * Math.sin(longitudeDelta / 2.0);
        return 2.0 * EARTH_RADIUS_METERS
            * Math.asin(Math.min(1.0, Math.sqrt(haversine)));
    }

    private boolean invalidAccuracy(Double accuracyMeters) {
        return accuracyMeters != null
            && (!Double.isFinite(accuracyMeters)
                || accuracyMeters < 0
                || accuracyMeters > properties.locationMaxAccuracyMeters());
    }

    private static boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude)
            && latitude >= -90.0
            && latitude <= 90.0
            && Double.isFinite(longitude)
            && longitude >= -180.0
            && longitude <= 180.0;
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = longitude;
        while (normalized < -180.0) {
            normalized += 360.0;
        }
        while (normalized > 180.0) {
            normalized -= 360.0;
        }
        return normalized;
    }

    public record Resolution(String status, Long placeId, String placeName) {
        static Resolution noMatch() {
            return new Resolution("no_match", null, null);
        }
    }

    private record MeasuredCandidate(
        PhotoPlaceRepository.Candidate candidate,
        double distanceMeters
    ) {
    }
}
