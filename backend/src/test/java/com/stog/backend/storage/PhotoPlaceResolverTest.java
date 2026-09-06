package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PhotoPlaceResolverTest {
    private static final double LATITUDE = 35.815;
    private static final double LONGITUDE = 127.15;

    private PhotoPlaceRepository places;
    private PhotoPlaceResolver resolver;

    @BeforeEach
    void setUp() {
        places = mock(PhotoPlaceRepository.class);
        resolver = new PhotoPlaceResolver(places, new SetLogProperties(120, 50, 75, 10));
    }

    @Test
    void choosesNearestWithinRadiusAfterExactJavaDistanceAndStableOrdering() {
        whenCandidates(List.of(
            candidate(20L, "Forty meters", 40.0),
            candidate(10L, "Twenty meters", 20.0)
        ));

        PhotoPlaceResolver.Resolution result = resolver.resolve(LATITUDE, LONGITUDE, 12.0);

        assertThat(result).isEqualTo(
            new PhotoPlaceResolver.Resolution("matched", 10L, "Twenty meters")
        );
        assertThat(PhotoPlaceResolver.distanceMeters(
            LATITUDE, LONGITUDE, LATITUDE + latitudeDegrees(20.0), LONGITUDE
        )).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void returnsNoMatchForTieEmptyOverRadiusAndStructurallyInvalidCandidate() {
        whenCandidates(List.of(
            candidate(2L, "Second stable id", 20.0),
            candidate(1L, "First stable id", 20.0)
        ));
        assertThat(resolver.resolve(LATITUDE, LONGITUDE, null).status()).isEqualTo("no_match");

        whenCandidates(List.of());
        assertThat(resolver.resolve(LATITUDE, LONGITUDE, null).status()).isEqualTo("no_match");

        whenCandidates(List.of(candidate(3L, "Too far", 75.1)));
        assertThat(resolver.resolve(LATITUDE, LONGITUDE, null).status()).isEqualTo("no_match");

        whenCandidates(List.of(new PhotoPlaceRepository.Candidate(4L, "Invalid", 91.0, 0.0)));
        assertThat(resolver.resolve(LATITUDE, LONGITUDE, null).status()).isEqualTo("no_match");
    }

    @Test
    void rejectsInvalidCameraFixesWithoutQueryingStorage() {
        assertThat(resolver.resolve(Double.NaN, LONGITUDE, 5.0).status()).isEqualTo("no_match");
        assertThat(resolver.resolve(91.0, LONGITUDE, 5.0).status()).isEqualTo("no_match");
        assertThat(resolver.resolve(LATITUDE, LONGITUDE, 50.01).status()).isEqualTo("no_match");
        verifyNoInteractions(places);
    }

    @Test
    void convertsRepositoryFailureToTypedRetryableBoundary() {
        whenCandidatesFailure(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> resolver.resolve(LATITUDE, LONGITUDE, 5.0))
            .isInstanceOf(PhotoApiException.class)
            .satisfies(error -> {
                PhotoApiException typed = (PhotoApiException) error;
                assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(typed.code()).isEqualTo("PHOTO_PLACE_RESOLUTION_FAILED");
            });
    }

    private PhotoPlaceRepository.Candidate candidate(long id, String name, double northMeters) {
        return new PhotoPlaceRepository.Candidate(
            id, name, LATITUDE + latitudeDegrees(northMeters), LONGITUDE
        );
    }

    private static double latitudeDegrees(double meters) {
        return Math.toDegrees(meters / 6_371_008.8);
    }

    private void whenCandidates(List<PhotoPlaceRepository.Candidate> candidates) {
        when(places.findEligibleCandidates(
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyBoolean()
        )).thenReturn(candidates);
    }

    private void whenCandidatesFailure(RuntimeException failure) {
        when(places.findEligibleCandidates(
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyBoolean()
        )).thenThrow(failure);
    }
}
