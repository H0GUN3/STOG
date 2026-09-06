package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
@Import(PlanningServiceTest.TestGooglePlacesConfiguration.class)
class PlanningServiceTest {
    @Autowired
    private PlanningService planning;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GooglePlacesClient googlePlaces;

    @Test
    void confirmedGooglePlaceIsReusedAndAddedToBasket() {
        long ownerId = user("owner");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create(
                "전주 여행",
                "tour",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 3)
            )
        );
        when(googlePlaces.details("ChIJplace")).thenReturn(new PlaceCandidate(
            "google",
            "ChIJplace",
            "전주 카페",
            "전주 주소",
            35.815,
            127.15,
            List.of("cafe"),
            List.of(),
            null,
            null,
            null,
            List.of()
        ));
        BasketResponses.Added first = planning.addPlace(
            ownerId,
            new BasketRequests.AddPlace(
                trip.id(),
                "google",
                "ChIJplace",
                "전주 카페",
                "cafe",
                35.815,
                127.15
            )
        );
        BasketResponses.Added second = planning.addPlace(
            ownerId,
            new BasketRequests.AddPlace(
                trip.id(),
                "google",
                "ChIJplace",
                "전주 카페",
                "cafe",
                35.815,
                127.15
            )
        );

        assertThat(first.place_id()).isEqualTo(second.place_id());
        assertThat(first.status()).isEqualTo("resolved");
        assertThat(first.cell_id()).isNull();
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM places WHERE source = 'google' AND external_id = 'ChIJplace'"
            )
            .query(Long.class)
            .single()).isEqualTo(1L);
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM basket_items WHERE trip_id = :tripId"
            )
            .param("tripId", trip.id())
            .query(Long.class)
            .single()).isEqualTo(2L);
        assertThat(jdbc.sql(
                "SELECT catalog_status || '|' || COALESCE(cell_id::text, 'null') FROM places WHERE id = :placeId"
            )
            .param("placeId", first.place_id())
            .query(String.class)
            .single()).isEqualTo("private_reference|null");
    }

    @Test
    void canonicalCatalogCandidateCanBeAddedWithoutMutatingTheCleanedPlace() {
        long ownerId = user("canonical-owner");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("카탈로그 여행", "tour", null, null)
        );
        CanonicalPlace canonical = canonicalPlace("tour-api-123", "전주 한옥마을");
        String before = placeReceipt(canonical.placeId());

        BasketResponses.Added added = planning.addPlace(
            ownerId,
            new BasketRequests.AddPlace(
                trip.id(),
                "canonical",
                "tour-api-123",
                "변조된 이름",
                "cafe",
                36.0,
                128.0,
                canonical.placeId(),
                canonical.sourceRecordId()
            )
        );

        assertThat(added.status()).isEqualTo("resolved");
        assertThat(added.place_id()).isEqualTo(canonical.placeId());
        assertThat(added.cell_id()).isNotBlank();
        assertThat(placeReceipt(canonical.placeId())).isEqualTo(before);
        assertThat(jdbc.sql(
                "SELECT source || '|' || title FROM basket_items WHERE id = :id"
            )
            .param("id", added.id())
            .query(String.class)
            .single()).isEqualTo("public_data|전주 한옥마을");
    }

    @Test
    void canonicalCatalogReferenceMustMatchAnEligibleSourceRecord() {
        long ownerId = user("canonical-invalid-owner");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("카탈로그 검증 여행", "tour", null, null)
        );
        CanonicalPlace canonical = canonicalPlace("tour-api-invalid", "검증 장소");

        assertThatThrownBy(() -> planning.addPlace(
            ownerId,
            new BasketRequests.AddPlace(
                trip.id(),
                "canonical",
                "tour-api-invalid",
                "검증 장소",
                "attraction",
                35.815,
                127.15,
                canonical.placeId(),
                canonical.sourceRecordId() + 1
            )
        )).isInstanceOf(ResponseStatusException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM basket_items WHERE trip_id = :tripId")
            .param("tripId", trip.id())
            .query(Long.class)
            .single()).isZero();
    }

    @Test
    void nonOwnerCannotAddToTripBasket() {
        long ownerId = user("owner");
        long otherId = user("other");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("여행", "walk", null, null)
        );

        assertThatThrownBy(() -> planning.addPlace(
            otherId,
            new BasketRequests.AddPlace(
                trip.id(),
                "google",
                "ChIJblocked",
                "장소",
                "cafe",
                35.815,
                127.15
            )
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Trip access denied");
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM places WHERE source = 'google' AND external_id = 'ChIJblocked'"
            )
            .query(Long.class)
            .single()).isZero();
    }

    @Test
    void sameClientItemAndPayloadReturnsOriginalWhileChangedPayloadConflicts() {
        long ownerId = user("idempotent-owner");
        TripResponses.Created trip = planning.createTrip(
            ownerId,
            new TripRequests.Create("멱등 여행", "tour", null, null)
        );
        when(googlePlaces.details("ChIJstable")).thenReturn(new PlaceCandidate(
            "google", "ChIJstable", "확정 장소", "주소", 35.815, 127.15,
            List.of("cafe"), List.of(), null, null, null, List.of()
        ));
        String clientItemId = "c0a80101-0000-4000-8000-000000000009";
        BasketRequests.AddPlace original = placeRequest(
            trip.id(), clientItemId, "ChIJstable", "확정 장소"
        );

        BasketResponses.Added first = planning.addPlace(ownerId, original);
        BasketResponses.Added replay = planning.addPlace(ownerId, original);
        BasketRequests.AddPlace changed = placeRequest(
            trip.id(), clientItemId, "ChIJstable", "변경된 장소"
        );

        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> planning.addPlace(ownerId, changed))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM basket_items WHERE trip_id = :tripId")
            .param("tripId", trip.id()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT client_item_id || '|' || payload_fingerprint FROM basket_items WHERE id = :id")
            .param("id", first.id()).query(String.class).single())
            .isEqualTo(clientItemId + "|" + original.payload_fingerprint());
    }

    private BasketRequests.AddPlace placeRequest(
        long tripId,
        String clientItemId,
        String externalId,
        String name
    ) {
        String category = "cafe";
        double latitude = 35.815;
        double longitude = 127.15;
        String fingerprint = BasketPayloadFingerprint.forPlace(
            tripId, "google", externalId, name, category, latitude, longitude, null, null
        );
        return new BasketRequests.AddPlace(
            tripId, clientItemId, fingerprint, "google", externalId, name, category,
            latitude, longitude, null, null
        );
    }

    @Test
    void listTripsReturnsOnlyOwnerTripsNewestFirst() {
        long ownerId = user("owner");
        long otherId = user("other");
        planning.createTrip(ownerId, new TripRequests.Create("먼저", "tour", null, null));
        planning.createTrip(otherId, new TripRequests.Create("다른 여행", "walk", null, null));
        planning.createTrip(ownerId, new TripRequests.Create("나중", "date", null, null));

        assertThat(planning.listTrips(ownerId))
            .extracting(TripResponses.Summary::title)
            .containsExactly("나중", "먼저");
    }

    private CanonicalPlace canonicalPlace(String externalId, String name) {
        long sourceId = jdbc.sql("""
                INSERT INTO catalog_sources (source_key, name, provider_type, active)
                VALUES (:sourceKey, 'Planning canonical source', 'tour_api', TRUE)
                RETURNING id
                """)
            .param("sourceKey", "planning-" + externalId)
            .query(Long.class)
            .single();
        long licenseId = jdbc.sql("""
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    :sourceId, 'Planning canonical license', CURRENT_TIMESTAMP,
                    CURRENT_DATE - 1, TRUE, '["name","coordinates"]'::jsonb, :digest
                )
                RETURNING id
                """)
            .param("sourceId", sourceId)
            .param("digest", "planning-license-" + externalId)
            .query(Long.class)
            .single();
        long cellId = com.stog.backend.cell.CellIdCalculator.fromCoords(35.815, 127.15);
        long placeId = jdbc.sql("""
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, external_id,
                    normalized_name, compact_name, public_cell_eligible
                )
                VALUES (
                    :name, 'ATTRACTION', 35.815, 127.15, :cellId, 'public_data', :externalId,
                    :normalizedName, :compactName, TRUE
                )
                RETURNING id
                """)
            .param("name", name)
            .param("cellId", cellId)
            .param("externalId", externalId)
            .param("normalizedName", name)
            .param("compactName", name.replace(" ", ""))
            .query(Long.class)
            .single();
        long sourceRecordId = jdbc.sql("""
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, imported_at
                )
                VALUES (
                    :placeId, :sourceId, :licenseId, :externalId,
                    :sourceDigest, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """)
            .param("placeId", placeId)
            .param("sourceId", sourceId)
            .param("licenseId", licenseId)
            .param("externalId", externalId)
            .param("sourceDigest", "planning-source-" + externalId)
            .query(Long.class)
            .single();
        jdbc.sql("UPDATE places SET catalog_status = 'public' WHERE id = :placeId")
            .param("placeId", placeId)
            .update();
        return new CanonicalPlace(placeId, sourceRecordId);
    }

    private String placeReceipt(long placeId) {
        return jdbc.sql("""
                SELECT row_to_json(place_row)::text
                FROM (SELECT * FROM places WHERE id = :placeId) place_row
                """)
            .param("placeId", placeId)
            .query(String.class)
            .single();
    }

    private long user(String nickname) {
        return jdbc.sql(
                "INSERT INTO users (nickname) VALUES (:nickname) RETURNING id"
            )
            .param("nickname", nickname)
            .query(Long.class)
            .single();
    }

    private record CanonicalPlace(long placeId, long sourceRecordId) {
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
