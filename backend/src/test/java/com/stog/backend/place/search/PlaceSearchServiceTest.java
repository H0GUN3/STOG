package com.stog.backend.place.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.google.GoogleProviderException;
import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import com.stog.backend.place.PlaceRequests;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
@Import(PlaceSearchServiceTest.TestGooglePlacesConfiguration.class)
class PlaceSearchServiceTest {
    @Autowired
    private PlaceSearchService search;

    @Autowired
    private PlaceSearchNormalizer normalizer;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GooglePlacesClient googlePlaces;

    private Long catalogSourceId;
    private Long licenseSnapshotId;

    @BeforeEach
    void resetProvider() {
        reset(googlePlaces);
    }

    @Test
    void ranksExactNameThenCompactNameThenAliasThenFuzzyCandidates() {
        long exactId = place("전주 한옥마을", "exact");
        long compactId = place("전주한옥마을", "compact");
        long aliasId = place("전주 전통마을", "alias");
        alias(aliasId, "전주 한옥마을");
        long fuzzyId = place("전주 한옥마을길", "fuzzy");

        PlaceSearchResponse response = search.search(request("전주 한옥마을"));

        assertThat(response.candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .containsExactly(exactId, compactId, aliasId, fuzzyId);
        assertThat(response.candidates().get(0).provenance())
            .isEqualTo(PlaceSearchProvenance.canonical(
                exactId,
                "public_data",
                sourceRecordIdFor(exactId),
                "public"
            ));
        verifyNoInteractions(googlePlaces);
    }

    @Test
    void keepsEqualRankResultsInStableCanonicalIdOrder() {
        long firstId = place("첫 번째 장소", "first");
        long secondId = place("두 번째 장소", "second");
        alias(firstId, "공통 별칭");
        alias(secondId, "공통 별칭");
        when(googlePlaces.textSearch(any())).thenReturn(List.of());

        List<Long> expected = List.of(firstId, secondId);
        assertThat(search.search(request("공통 별칭")).candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .containsExactlyElementsOf(expected);
        assertThat(search.search(request("공통 별칭")).candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .containsExactlyElementsOf(expected);
        verify(googlePlaces, times(2)).textSearch(any());
    }

    @Test
    void recallsKoreanSpacingPunctuationAndTypoVariants() {
        long placeId = place("전주 한옥마을", "hanok");
        when(googlePlaces.textSearch(any())).thenReturn(List.of());

        assertThat(search.search(request("전주·한옥마을!!")).candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .contains(placeId);
        assertThat(search.search(request("전주한옥마을")).candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .contains(placeId);
        assertThat(search.search(request("전쥬 한옥마을")).candidates())
            .extracting(candidate -> candidate.provenance().place_id())
            .contains(placeId);
    }

    @Test
    void usesGoogleForGeneralPlaceResultsEvenWhenTheLocalCountGateIsMet() {
        long firstId = place("전주 카페 하나", "cafe-one", "CAFE");
        long secondId = place("전주 카페 둘", "cafe-two", "CAFE");
        long thirdId = place("전주 카페 셋", "cafe-three", "CAFE");
        PlaceCandidate providerCandidate = providerCandidate("ChIJgeneral");
        when(googlePlaces.textSearch(any())).thenReturn(List.of(providerCandidate));

        PlaceSearchResponse response = search.search(request("전주 카페"));

        assertThat(response.candidates().subList(0, 3))
            .extracting(candidate -> candidate.provenance().place_id())
            .containsExactlyInAnyOrder(firstId, secondId, thirdId);
        assertThat(response.candidates().subList(0, 3))
            .allSatisfy(candidate -> assertThat(candidate.types()).containsExactly("cafe"));
        assertThat(response.candidates().get(3))
            .isEqualTo(PlaceSearchResult.provider(providerCandidate));
        verify(googlePlaces, times(1)).textSearch(any());
    }

    @Test
    void fallsBackToGoogleExactlyOnceWithoutMutatingTheCatalog() {
        long catalogCount = catalogCount();
        PlaceCandidate providerCandidate = providerCandidate("ChIJfallback");
        when(googlePlaces.textSearch(any())).thenReturn(List.of(providerCandidate));

        PlaceSearchResponse response = search.search(request("없는 장소"));

        assertThat(response.candidates()).containsExactly(
            PlaceSearchResult.provider(providerCandidate)
        );
        assertThat(catalogCount()).isEqualTo(catalogCount);
        verify(googlePlaces, times(1)).textSearch(any());
    }

    @Test
    void rejectsBlankQueryAndInvalidCoordinatesOrRadiusBeforeSearching() {
        assertBadRequest(request(" -- !! "));
        assertBadRequest(new PlaceRequests.Search("전주", 35.8, null, null, null));
        assertBadRequest(new PlaceRequests.Search("전주", 91.0, 127.1, 1000.0, null));
        assertBadRequest(new PlaceRequests.Search("전주", 35.8, 127.1, 0.0, null));

        verifyNoInteractions(googlePlaces);
    }

    @Test
    void preservesTypedProviderFailuresWithoutCatalogMutation() {
        long catalogCount = catalogCount();
        GoogleProviderException providerFailure = new GoogleProviderException(
            HttpStatus.BAD_GATEWAY,
            "GOOGLE_PLACES_SEARCH_FAILED",
            true
        );
        when(googlePlaces.textSearch(any())).thenThrow(providerFailure);

        assertThatThrownBy(() -> search.search(request("provider failure")))
            .isSameAs(providerFailure);

        assertThat(catalogCount()).isEqualTo(catalogCount);
        verify(googlePlaces, times(1)).textSearch(any());
    }

    private void assertBadRequest(PlaceRequests.Search request) {
        assertThatThrownBy(() -> search.search(request))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
            ).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nearbyMergesEligibleLocalPlacesWithProviderCandidates() {
        long localPlaceId = place("전주 한옥마을", "local-nearby");
        jdbc.sql("UPDATE places SET public_cell_eligible = TRUE WHERE id = :placeId")
            .param("placeId", localPlaceId)
            .update();
        when(googlePlaces.nearbySearch(any())).thenReturn(
            List.of(providerCandidate(
                "google-nearby",
                "전주 경기전",
                35.816,
                127.151,
                List.of("tourist_attraction"),
                List.of("places/google-nearby/photos/photo")
            ))
        );

        PlaceSearchResponse response = search.nearby(
            new PlaceRequests.Nearby(
                new PlaceRequests.Center(35.815, 127.15),
                5000.0,
                List.of("tourist_attraction"),
                2
            )
        );

        assertThat(response.candidates())
            .extracting(PlaceSearchResult::external_id)
            .containsExactly("google-nearby", "local-nearby");
        assertThat(response.candidates().get(0).provenance().kind())
            .isEqualTo("provider");
        assertThat(response.candidates().get(1).provenance().kind())
            .isEqualTo("canonical");
        verify(googlePlaces, times(1)).nearbySearch(any());
    }

    @Test
    void nearbyDeduplicatesSamePlaceAndPrioritizesPhotoCandidates() {
        long localDuplicateId = place("은파호수공원", "local-park");
        long localUniqueId = place("월명공원", "local-unique");
        jdbc.sql("UPDATE places SET public_cell_eligible = TRUE WHERE id IN (:first, :second)")
            .param("first", localDuplicateId)
            .param("second", localUniqueId)
            .update();
        when(googlePlaces.nearbySearch(any())).thenReturn(List.of(
            providerCandidate(
                "google-park",
                "은파호수공원",
                35.8151,
                127.1501,
                List.of("park"),
                List.of("places/google-park/photos/photo")
            ),
            providerCandidate(
                "google-cafe",
                "군산 카페",
                35.82,
                127.15,
                List.of("cafe"),
                List.of("places/google-cafe/photos/photo")
            )
        ));

        PlaceSearchResponse response = search.nearby(
            new PlaceRequests.Nearby(
                new PlaceRequests.Center(35.815, 127.15),
                5000.0,
                List.of("tourist_attraction", "park"),
                3
            )
        );

        assertThat(response.candidates())
            .extracting(PlaceSearchResult::external_id)
            .containsExactly("google-park", "local-unique");
        assertThat(response.candidates())
            .extracting(PlaceSearchResult::name)
            .doesNotHaveDuplicates();
        assertThat(response.candidates().get(0).photo_names()).isNotEmpty();
    }

    @Test
    void nearbyQueriesProviderWhenFullLocalResultsHaveNoPhotos() {
        long firstLocalId = place("문창서원", "local-first");
        long secondLocalId = place("자천대", "local-second");
        jdbc.sql("UPDATE places SET public_cell_eligible = TRUE WHERE id IN (:first, :second)")
            .param("first", firstLocalId)
            .param("second", secondLocalId)
            .update();
        when(googlePlaces.nearbySearch(any())).thenReturn(List.of(
            providerCandidate(
                "google-first",
                "문창서원",
                35.8151,
                127.1501,
                List.of("tourist_attraction"),
                List.of("places/google-first/photos/photo")
            ),
            providerCandidate(
                "google-second",
                "군산 근대문화유산",
                35.82,
                127.15,
                List.of("historical_landmark"),
                List.of("places/google-second/photos/photo")
            )
        ));

        PlaceSearchResponse response = search.nearby(
            new PlaceRequests.Nearby(
                new PlaceRequests.Center(35.815, 127.15),
                5000.0,
                List.of("tourist_attraction"),
                2
            )
        );

        assertThat(response.candidates())
            .extracting(PlaceSearchResult::external_id)
            .containsExactly("google-first", "google-second");
        verify(googlePlaces, times(1)).nearbySearch(any());
    }

    @Test
    void nearbyFiltersNonTourismTypesBeforeLocalAndProviderResults() {
        long localRestaurantId = place("전주 비빔밥 식당", "local-restaurant", "restaurant");
        jdbc.sql("UPDATE places SET public_cell_eligible = TRUE WHERE id = :placeId")
            .param("placeId", localRestaurantId)
            .update();
        when(googlePlaces.nearbySearch(any())).thenReturn(
            List.of(providerCandidate(
                "google-cafe",
                "전주 카페",
                35.815,
                127.15,
                List.of("cafe"),
                List.of("places/google-cafe/photos/photo")
            ))
        );

        PlaceSearchResponse response = search.nearby(
            new PlaceRequests.Nearby(
                new PlaceRequests.Center(35.815, 127.15),
                5000.0,
                List.of("tourist_attraction", "restaurant", "cafe"),
                3
            )
        );

        assertThat(response.candidates()).isEmpty();
        ArgumentCaptor<PlaceRequests.Nearby> request = ArgumentCaptor.forClass(
            PlaceRequests.Nearby.class
        );
        verify(googlePlaces).nearbySearch(request.capture());
        assertThat(request.getValue().included_types())
            .containsExactly("tourist_attraction");
    }

    @Test
    void nearbyReturnsApprovedCanonicalPhotoUrlsWithoutProviderFallback() {
        long localPlaceId = place("전주 한옥마을", "local-photo");
        jdbc.sql("UPDATE places SET public_cell_eligible = TRUE WHERE id = :placeId")
            .param("placeId", localPlaceId)
            .update();
        jdbc.sql(
                """
                INSERT INTO place_source_images (
                    place_source_record_id, source_image_id, source_url,
                    license_snapshot_id, source_digest, reusable
                )
                VALUES (
                    :sourceRecordId, 'hanok-cover', 'https://images.example.test/hanok.jpg',
                    :licenseSnapshotId, 'image-digest-hanok', TRUE
                )
                """
            )
            .param("sourceRecordId", sourceRecordIdFor(localPlaceId))
            .param("licenseSnapshotId", licenseSnapshotId)
            .update();

        PlaceSearchResponse response = search.nearby(
            new PlaceRequests.Nearby(
                new PlaceRequests.Center(35.815, 127.15),
                5000.0,
                List.of("tourist_attraction"),
                1
            )
        );

        assertThat(response.candidates()).singleElement()
            .extracting(PlaceSearchResult::photo_urls)
            .isEqualTo(List.of("https://images.example.test/hanok.jpg"));
        verifyNoInteractions(googlePlaces);
    }

    private PlaceRequests.Search request(String query) {
        return new PlaceRequests.Search(query, null, null, null, null);
    }

    private long place(String name, String externalId) {
        return place(name, externalId, "tourist_attraction");
    }

    private long place(String name, String externalId, String category) {
        ensureCatalogEligibility();
        PlaceSearchNormalizer.Normalized normalized = normalizer.normalize(name);
        long placeId = jdbc.sql(
                """
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, external_id,
                    normalized_name, compact_name
                )
                VALUES (
                    :name, :category, 35.815, 127.15, :cellId,
                    'public_data', :externalId, :normalizedName, :compactName
                )
                RETURNING id
                """
            )
            .param("name", name)
            .param("category", category)
            .param("cellId", CellIdCalculator.fromCoords(35.815, 127.15))
            .param("externalId", externalId)
            .param("normalizedName", normalized.normalized_name())
            .param("compactName", normalized.compact_name())
            .query(Long.class)
            .single();
        jdbc.sql(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (
                    :placeId, :catalogSourceId, :licenseSnapshotId, :externalId,
                    :sourceDigest, CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP
                )
                """
            )
            .param("placeId", placeId)
            .param("catalogSourceId", catalogSourceId)
            .param("licenseSnapshotId", licenseSnapshotId)
            .param("externalId", externalId)
            .param("sourceDigest", "source-" + UUID.randomUUID())
            .update();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return placeId;
    }

    private void alias(long placeId, String value) {
        PlaceSearchNormalizer.Normalized normalized = normalizer.normalize(value);
        jdbc.sql(
                """
                INSERT INTO place_aliases (
                    place_id, alias, normalized_name, compact_name, alias_source
                )
                VALUES (:placeId, :alias, :normalizedName, :compactName, 'fixture')
                """
            )
            .param("placeId", placeId)
            .param("alias", value)
            .param("normalizedName", normalized.normalized_name())
            .param("compactName", normalized.compact_name())
            .update();
    }

    private void ensureCatalogEligibility() {
        if (catalogSourceId != null) {
            return;
        }
        catalogSourceId = jdbc.sql(
                """
                INSERT INTO catalog_sources (source_key, name, provider_type, active)
                VALUES (:sourceKey, 'Search fixture', 'public_data', TRUE)
                RETURNING id
                """
            )
            .param("sourceKey", "search-" + UUID.randomUUID())
            .query(Long.class)
            .single();
        licenseSnapshotId = jdbc.sql(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :catalogSourceId, 'Fixture license', CURRENT_TIMESTAMP,
                    CURRENT_DATE - 1, TRUE, '["name", "image"]'::jsonb, :digest
                )
                RETURNING id
                """
            )
            .param("catalogSourceId", catalogSourceId)
            .param("digest", "license-" + UUID.randomUUID())
            .query(Long.class)
            .single();
    }

    private long sourceRecordIdFor(long placeId) {
        return jdbc.sql(
                "SELECT id FROM place_source_records WHERE place_id = :placeId"
            )
            .param("placeId", placeId)
            .query(Long.class)
            .single();
    }

    private long catalogCount() {
        return jdbc.sql("SELECT COUNT(*) FROM places")
            .query(Long.class)
            .single();
    }

    private PlaceCandidate providerCandidate(String externalId) {
        return providerCandidate(
            externalId,
            "Google fallback",
            35.815,
            127.15,
            List.of("cafe"),
            List.of("places/" + externalId + "/photos/photo")
        );
    }

    private PlaceCandidate providerCandidate(
        String externalId,
        String name,
        double latitude,
        double longitude,
        List<String> types,
        List<String> photoNames
    ) {
        return new PlaceCandidate(
            "google",
            externalId,
            name,
            "전북 전주시",
            latitude,
            longitude,
            types,
            List.of(),
            null,
            null,
            null,
            photoNames
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGooglePlacesConfiguration {
        @Bean
        @Primary
        GooglePlacesClient googlePlacesClient() {
            return mock(GooglePlacesClient.class);
        }
    }
}
