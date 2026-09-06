package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(BasketItemConcurrencyTest.GoogleConfig.class)
class BasketItemConcurrencyTest {
    @Autowired PlanningService planning;
    @Autowired JdbcClient jdbc;
    @Autowired GooglePlacesClient google;

    @Test
    void providerAndCanonicalFirstWriteRacesReturnOneImmutableResponse() throws Exception {
        String marker = "race-" + UUID.randomUUID();
        long user = jdbc.sql("INSERT INTO users(nickname) VALUES(:n) RETURNING id").param("n", marker).query(Long.class).single();
        long trip = jdbc.sql("INSERT INTO trips(owner_id,title,activity_type) VALUES(:u,:n,'tour') RETURNING id").param("u", user).param("n", marker).query(Long.class).single();
        when(google.details(marker)).thenReturn(new PlaceCandidate("google", marker, "provider", "address", 35.8, 127.1, List.of("cafe"), List.of(), null, null, null, List.of()));
        BasketRequests.AddPlace provider = request(trip, marker + "-provider-key", "google", marker, "carrier", null, null);
        Canonical canonical = canonical(marker);
        BasketRequests.AddPlace canonicalRequest = request(trip, marker + "-canonical-key", "canonical", marker + "-canonical", "carrier", canonical.placeId, canonical.sourceRecordId);
        try {
            assertRace(user, provider);
            assertRace(user, canonicalRequest);
            assertThat(jdbc.sql("SELECT count(*) FROM basket_items WHERE trip_id=:t").param("t", trip).query(Long.class).single()).isEqualTo(2);
        } finally {
            jdbc.sql("DELETE FROM trips WHERE owner_id=:u").param("u", user).update();
            jdbc.sql("DELETE FROM users WHERE id=:u").param("u", user).update();
            jdbc.sql("DELETE FROM places WHERE external_id=:e").param("e", marker).update();
            jdbc.sql("UPDATE places SET catalog_status='private_reference' WHERE id=:p").param("p", canonical.placeId).update();
            jdbc.sql("DELETE FROM place_source_records WHERE id=:r").param("r", canonical.sourceRecordId).update();
            jdbc.sql("DELETE FROM places WHERE id=:p").param("p", canonical.placeId).update();
            jdbc.sql("DELETE FROM license_snapshots WHERE digest=:d").param("d", marker).update();
            jdbc.sql("DELETE FROM catalog_sources WHERE source_key=:s").param("s", marker).update();
        }
    }

    private void assertRace(long user, BasketRequests.AddPlace request) throws Exception {
        int count = 6;
        CyclicBarrier barrier = new CyclicBarrier(count);
        var pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<BasketResponses.Added>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { barrier.await(); return planning.addPlace(user, request); }));
            List<BasketResponses.Added> results = new ArrayList<>();
            for (Future<BasketResponses.Added> future : futures) results.add(future.get());
            assertThat(results).allMatch(results.get(0)::equals);
        } finally { pool.shutdownNow(); }
    }

    private BasketRequests.AddPlace request(long trip, String key, String provider, String external, String name, Long place, Long source) {
        String fp = BasketPayloadFingerprint.forPlace(trip, provider, external, name, "cafe", 35.8, 127.1, place, source);
        return new BasketRequests.AddPlace(trip, key, fp, provider, external, name, "cafe", 35.8, 127.1, place, source);
    }

    private Canonical canonical(String marker) {
        long source = jdbc.sql("INSERT INTO catalog_sources(source_key,name,provider_type,active) VALUES(:m,:m,'tour_api',true) RETURNING id").param("m", marker).query(Long.class).single();
        long license = jdbc.sql("INSERT INTO license_snapshots(catalog_source_id,license_name,reviewed_at,valid_from,allows_public_discovery,reusable_fields,digest) VALUES(:s,:m,now(),current_date,true,jsonb_build_array('name'),:m) RETURNING id").param("s", source).param("m", marker).query(Long.class).single();
        long place = jdbc.sql("INSERT INTO places(name,category,lat,lng,source,external_id,normalized_name,compact_name,catalog_status) VALUES('race place','CAFE',35.8,127.1,'public_data',:e,'race place','raceplace','private_reference') RETURNING id").param("e", marker + "-canonical").query(Long.class).single();
        long record = jdbc.sql("INSERT INTO place_source_records(place_id,catalog_source_id,license_snapshot_id,external_id,source_digest,source_updated_at,imported_at) VALUES(:p,:s,:l,:e,:m,now(),now()) RETURNING id").param("p", place).param("s", source).param("l", license).param("e", marker + "-canonical").param("m", marker).query(Long.class).single();
        jdbc.sql("UPDATE places SET catalog_status='public' WHERE id=:p").param("p", place).update();
        return new Canonical(place, record);
    }

    record Canonical(long placeId, long sourceRecordId) {}
    @TestConfiguration(proxyBeanMethods=false) static class GoogleConfig {
        @Bean @Primary GooglePlacesClient googlePlacesClient() { return mock(GooglePlacesClient.class); }
    }
}
