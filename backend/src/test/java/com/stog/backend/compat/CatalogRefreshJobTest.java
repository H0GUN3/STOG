package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stog.backend.place.search.PlaceSearchNormalizer;
import com.stog.backend.storage.GcsObjectClient;
import com.stog.backend.storage.StorageProperties;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "stog.catalog.refresh.tour-api.url=https://catalog.example.test/tour-api",
    "stog.catalog.refresh.tour-api.service-key=test-service-key"
})
@Import(CatalogRefreshJobTest.RefreshTestConfiguration.class)
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CatalogRefreshJobTest {
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private CatalogRefreshJob job;

    @Autowired
    private TestCatalogRefreshProvider provider;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @Test
    void approvedPublicResponseCreatesSourceSnapshotAndImportsEligibleRows() throws Exception {
        CloudPlaceSourceRow approved = variant(fixtureRows().get(0), "approved");
        provider.respondWith(complete(approved));

        CatalogRefreshResult result = job.refresh();

        assertThat(result.status()).isEqualTo(CatalogRefreshResult.Status.SUCCEEDED);
        assertThat(result.runId()).isNotBlank();
        assertThat(result.sourceSnapshotDigest()).hasSize(64);
        assertThat(result.importResult()).isEqualTo(new CanonicalPlaceImportResult(
            result.importResult().manifestDigest(), 1, 1, 0, 0, 1, 0, 0, false
        ));
        assertThat(placeCount(approved.sourceItemId())).isEqualTo(1);
        assertThat(scalarLong(
            "SELECT COUNT(*) FROM catalog_import_runs WHERE manifest_digest = :manifestDigest",
            result.importResult().manifestDigest()
        )).isEqualTo(1);
        assertThat(provider.runIds()).containsExactly(result.runId());
    }

    @Test
    void repeatedIdenticalPublicResponseIsNoOp() throws Exception {
        CloudPlaceSourceRow approved = variant(fixtureRows().get(0), "identical");
        provider.respondWith(complete(approved));

        CatalogRefreshResult first = job.refresh();
        CatalogRefreshResult second = job.refresh();

        assertThat(first.status()).isEqualTo(CatalogRefreshResult.Status.SUCCEEDED);
        assertThat(second.status()).isEqualTo(CatalogRefreshResult.Status.NO_OP);
        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(second.sourceSnapshotDigest()).isEqualTo(first.sourceSnapshotDigest());
        assertThat(second.importResult()).isEqualTo(new CanonicalPlaceImportResult(
            first.importResult().manifestDigest(), 1, 1, 0, 0, 0, 0, 1, true
        ));
        assertThat(placeCount(approved.sourceItemId())).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(1);
        assertThat(provider.runIds()).hasSize(2).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0} leaves the serving catalog unchanged")
    @MethodSource("providerFailures")
    void providerFailureQuotaAndTimeoutLeaveServingCatalogUnchanged(String failureCode) throws Exception {
        CloudPlaceSourceRow prior = variant(fixtureRows().get(0), "provider-prior-" + failureCode);
        provider.respondWith(complete(prior));
        CatalogRefreshResult initial = job.refresh();
        String before = catalogState(prior.sourceItemId());

        provider.failWith(failureCode);
        CatalogRefreshResult failed = job.refresh();

        assertThat(failed.status()).isEqualTo(CatalogRefreshResult.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo(failureCode);
        assertThat(failed.importResult()).isNull();
        assertThat(catalogState(prior.sourceItemId())).isEqualTo(before);
        assertThat(scalarLong("SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(1);
        assertThat(initial.status()).isEqualTo(CatalogRefreshResult.Status.SUCCEEDED);
    }

    @ParameterizedTest(name = "{0} response leaves the serving catalog unchanged")
    @MethodSource("malformedOrIncompleteResponses")
    void malformedOrIncompletePageLeavesServingCatalogUnchanged(
        String expectedFailure,
        PublicCatalogSnapshot invalid
    ) throws Exception {
        CloudPlaceSourceRow prior = variant(fixtureRows().get(0), "page-prior-" + expectedFailure);
        provider.respondWith(complete(prior));
        job.refresh();
        String before = catalogState(prior.sourceItemId());

        provider.respondWith(invalid);
        CatalogRefreshResult failed = job.refresh();

        assertThat(failed.status()).isEqualTo(CatalogRefreshResult.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo(expectedFailure);
        assertThat(catalogState(prior.sourceItemId())).isEqualTo(before);
        assertThat(scalarLong("SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(1);
    }

    @Test
    void unknownLicenseLeavesServingCatalogUnchanged() throws Exception {
        CloudPlaceSourceRow prior = variant(fixtureRows().get(0), "license-prior");
        provider.respondWith(complete(prior));
        job.refresh();
        String before = catalogState(prior.sourceItemId());

        provider.respondWith(new PublicCatalogSnapshot(
            "TOUR_API",
            "UNREVIEWED",
            true,
            List.of(variant(fixtureRows().get(0), "license-unknown"))
        ));
        CatalogRefreshResult failed = job.refresh();

        assertThat(failed.status()).isEqualTo(CatalogRefreshResult.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo("unknown_license");
        assertThat(catalogState(prior.sourceItemId())).isEqualTo(before);
        assertThat(scalarLong("SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(1);
    }

    @Test
    void failedSnapshotCannotPartiallyReplaceServingCatalog() throws Exception {
        CloudPlaceSourceRow prior = variant(fixtureRows().get(0), "atomic-prior");
        provider.respondWith(complete(prior));
        job.refresh();
        String before = catalogState(prior.sourceItemId());

        CloudPlaceSourceRow changed = change(prior, node -> {
            node.put("name", "전주 한옥마을 partial replacement");
            node.put("raw_digest", "task-10-raw-partial-replacement");
            node.put("source_updated_at", "2026-08-20T14:00:00Z");
        });
        CloudPlaceSourceRow unknownLicense = change(
            variant(fixtureRows().get(0), "atomic-unknown-license"),
            node -> node.put("license_decision", "UNREVIEWED")
        );
        provider.respondWith(new PublicCatalogSnapshot(
            "TOUR_API",
            "APPROVED_PUBLIC_REUSE",
            true,
            List.of(changed, unknownLicense)
        ));

        CatalogRefreshResult failed = job.refresh();

        assertThat(failed.status()).isEqualTo(CatalogRefreshResult.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo("unknown_license");
        assertThat(catalogState(prior.sourceItemId())).isEqualTo(before);
        assertThat(scalarLong("SELECT COUNT(*) FROM catalog_import_runs")).isEqualTo(1);
        assertThat(placeCount(unknownLicense.sourceItemId())).isZero();
    }

    private static Stream<String> providerFailures() {
        return Stream.of("provider_failure", "quota_exhausted", "provider_timeout");
    }

    private Stream<org.junit.jupiter.params.provider.Arguments> malformedOrIncompleteResponses()
        throws Exception {
        CloudPlaceSourceRow row = variant(fixtureRows().get(0), "incomplete-page");
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("malformed_response", (Object) null),
            org.junit.jupiter.params.provider.Arguments.of(
                "incomplete_page",
                new PublicCatalogSnapshot("TOUR_API", "APPROVED_PUBLIC_REUSE", false, List.of(row))
            )
        );
    }

    private PublicCatalogSnapshot complete(CloudPlaceSourceRow... rows) {
        return new PublicCatalogSnapshot(
            "TOUR_API",
            "APPROVED_PUBLIC_REUSE",
            true,
            List.of(rows)
        );
    }

    private List<CloudPlaceSourceRow> fixtureRows() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream("/compat/cloud-place-fixture.json")) {
            assertThat(fixture).isNotNull();
            return json.readValue(fixture, new TypeReference<>() {});
        }
    }

    private CloudPlaceSourceRow variant(CloudPlaceSourceRow source, String suffix) {
        return change(source, node -> {
            node.put("travel_item_id", "task-10-" + suffix);
            node.put("source_item_id", "task-10-" + suffix);
            node.put("raw_digest", "task-10-raw-" + suffix);
            node.set("external_references", json.createArrayNode());
        });
    }

    private CloudPlaceSourceRow change(
        CloudPlaceSourceRow source,
        Consumer<ObjectNode> changes
    ) {
        ObjectNode node = json.valueToTree(source);
        changes.accept(node);
        try {
            return json.treeToValue(node, CloudPlaceSourceRow.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private long placeCount(String externalId) {
        return jdbc.sql(
                "SELECT COUNT(*) FROM places WHERE source = 'public_data' AND external_id = :externalId"
            )
            .param("externalId", externalId)
            .query(Long.class)
            .single();
    }

    private String catalogState(String externalId) {
        return jdbc.sql(
                """
                SELECT place.name || '|' || place.catalog_status || '|' || record.source_digest
                FROM places place
                JOIN place_source_records record ON record.place_id = place.id
                WHERE place.source = 'public_data' AND place.external_id = :externalId
                """
            )
            .param("externalId", externalId)
            .query(String.class)
            .single();
    }

    private long scalarLong(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long scalarLong(String sql, String manifestDigest) {
        return jdbc.sql(sql)
            .param("manifestDigest", manifestDigest)
            .query(Long.class)
            .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RefreshTestConfiguration {
        @Bean
        CanonicalPlaceImporter canonicalPlaceImporter(
            JdbcClient jdbc,
            DataSource dataSource,
            PlaceSearchNormalizer names
        ) {
            return new CanonicalPlaceImporter(jdbc, dataSource, names);
        }

        @Bean
        CatalogRefreshJob catalogRefreshJob(
            CatalogRefreshProvider provider,
            CloudPlaceDryRunService manifests,
            CanonicalPlaceImporter importer,
            Environment environment,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            ObjectProvider<GcsObjectClient> gcsObjects,
            StorageProperties storageProperties,
            TourPhotoGalleryProperties photoProperties
        ) {
            return new CatalogRefreshJob(
                provider,
                manifests,
                importer,
                environment,
                transactionManager,
                jdbc,
                gcsObjects,
                storageProperties,
                photoProperties
            );
        }

        @Bean
        TestCatalogRefreshProvider testCatalogRefreshProvider() {
            return new TestCatalogRefreshProvider();
        }

        @Bean
        @Primary
        CatalogRefreshProvider catalogRefreshProvider(TestCatalogRefreshProvider provider) {
            return provider;
        }
    }

    static final class TestCatalogRefreshProvider implements CatalogRefreshProvider {
        private final List<String> runIds = new ArrayList<>();
        private PublicCatalogSnapshot snapshot;
        private String failureCode;

        void reset() {
            runIds.clear();
            snapshot = null;
            failureCode = null;
        }

        void respondWith(PublicCatalogSnapshot snapshot) {
            this.snapshot = snapshot;
            this.failureCode = null;
        }

        void failWith(String failureCode) {
            this.failureCode = failureCode;
            this.snapshot = null;
        }

        List<String> runIds() {
            return List.copyOf(runIds);
        }

        @Override
        public PublicCatalogSnapshot fetch(String runId) {
            runIds.add(runId);
            if (failureCode != null) {
                throw new CatalogRefreshProviderException(failureCode);
            }
            return snapshot;
        }
    }
}
