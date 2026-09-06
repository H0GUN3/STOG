package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stog.backend.cell.CellIdCalculator;
import com.stog.backend.place.search.PlaceSearchNormalizer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(CanonicalPlaceImporterTest.ImporterTestConfiguration.class)
@Transactional
public class CanonicalPlaceImporterTest {
    private static final Path FIXTURE_PATH = Path.of("src/test/resources/compat/cloud-place-fixture.json");

    private final ObjectMapper json = new ObjectMapper();
    private final CloudPlaceDryRunService manifests = new CloudPlaceDryRunService(null);

    @Autowired
    private CanonicalPlaceImporter importer;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void approvedRowCreatesCanonicalPlaceDetailsAliasSourceProvenanceAndH3Ten() throws Exception {
        byte[] sourceBefore = Files.readAllBytes(FIXTURE_PATH);
        CloudPlaceSourceRow approved = variant(fixtureRows().get(0), "approved", "primary");
        CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(List.of(approved));

        CanonicalPlaceImportResult result = importer.importManifest(manifest);

        assertThat(result).isEqualTo(new CanonicalPlaceImportResult(
            manifest.manifestDigest(), 1, 1, 0, 0, 1, 0, 0, false
        ));
        long placeId = placeId(approved.sourceItemId());
        assertThat(placeCount(approved.sourceItemId())).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM place_details WHERE place_id = :placeId", placeId))
            .isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM place_aliases WHERE place_id = :placeId", placeId))
            .isEqualTo(1);
        assertThat(scalarLong(
            "SELECT COUNT(*) FROM place_source_records WHERE place_id = :placeId", placeId
        )).isEqualTo(1);
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM catalog_import_provenance WHERE source_travel_item_id = :sourceId"
            )
            .param("sourceId", approved.travelItemId())
            .query(Long.class)
            .single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT cell_id FROM places WHERE id = :placeId")
            .param("placeId", placeId)
            .query(Long.class)
            .single()).isEqualTo(CellIdCalculator.fromCoords(approved.latitude(), approved.longitude()));
        assertThat(jdbc.sql("SELECT catalog_status FROM places WHERE id = :placeId")
            .param("placeId", placeId)
            .query(String.class)
            .single()).isEqualTo("public");
        assertThat(jdbc.sql(
                "SELECT source_digest FROM place_source_records WHERE place_id = :placeId"
            )
            .param("placeId", placeId)
            .query(String.class)
            .single()).isEqualTo(approved.rawDigest());
        assertThat(jdbc.sql(
                """
                SELECT legacy_h3_index || '|' || license_decision || '|'
                       || (provider_ids -> 'primary' ->> 'source_item_id')
                FROM catalog_import_provenance
                WHERE source_travel_item_id = :sourceId
                """
            )
            .param("sourceId", approved.travelItemId())
            .query(String.class)
            .single()).isEqualTo("8a1fb46622dffff|APPROVED_PUBLIC_REUSE|task-09-primary");
        assertThat(jdbc.sql(
                """
                SELECT source_row_count = importable_count + quarantined_count + preserved_only_count
                FROM catalog_import_runs
                WHERE manifest_digest = :manifestDigest
                """
            )
            .param("manifestDigest", manifest.manifestDigest())
            .query(Boolean.class)
            .single()).isTrue();
        assertThat(Files.readAllBytes(FIXTURE_PATH)).isEqualTo(sourceBefore);
    }

    @Test
    void importsOnlyExplicitlyReusableImagesWithImageFieldRights() throws Exception {
        CloudPlaceSourceRow source = change(fixtureRows().get(0), node -> {
            node.put("travel_item_id", "task-09-image-rights");
            node.put("source_item_id", "task-09-image-rights");
            node.put("raw_digest", "task-09-raw-image-rights");
            node.put("public_cell_eligible", true);
            ArrayNode reusableFields = json.createArrayNode();
            reusableFields.add("address");
            reusableFields.add("image");
            reusableFields.add("name");
            node.set("reusable_fields", reusableFields);
            ArrayNode images = json.createArrayNode();
            images.addObject()
                .put("source_image_id", "tour-api:firstimage")
                .put("source_url", "https://images.example.test/tour-api-firstimage.jpg")
                .put("source_digest", "image-digest-approved")
                .put("reusable", true);
            images.addObject()
                .put("source_image_id", "tour-api:secondimage")
                .put("source_url", "https://images.example.test/tour-api-secondimage.jpg")
                .put("source_digest", "image-digest-not-approved")
                .put("reusable", false);
            images.addObject()
                .put("source_image_id", "tour-api:missing-url")
                .put("source_digest", "image-digest-missing-url")
                .put("reusable", true);
            images.addObject()
                .put("source_image_id", "tour-api:insecure-url")
                .put("source_url", "http://images.example.test/insecure.jpg")
                .put("source_digest", "image-digest-insecure")
                .put("reusable", true);
            node.set("source_images", images);
            node.set("external_references", json.createArrayNode());
        });

        CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(List.of(source));
        importer.importManifest(manifest);

        long placeId = placeId(source.sourceItemId());
        assertThat(jdbc.sql(
                """
                SELECT source_image_id || '|' || source_url || '|' || reusable
                FROM place_source_images image
                JOIN place_source_records record
                  ON record.id = image.place_source_record_id
                WHERE record.place_id = :placeId
                """
            )
            .param("placeId", placeId)
            .query(String.class)
            .list())
            .containsExactly("tour-api:firstimage|https://images.example.test/tour-api-firstimage.jpg|true");
    }

    @Test
    void omitsImagesWhenParentLicenseDoesNotAllowImageReuse() throws Exception {
        CloudPlaceSourceRow source = change(fixtureRows().get(0), node -> {
            node.put("travel_item_id", "task-09-image-rights-denied");
            node.put("source_item_id", "task-09-image-rights-denied");
            node.put("raw_digest", "task-09-raw-image-rights-denied");
            node.set("reusable_fields", json.createArrayNode()
                .add("address")
                .add("name"));
            ArrayNode images = json.createArrayNode();
            images.addObject()
                .put("source_image_id", "tour-api:firstimage")
                .put("source_url", "https://images.example.test/tour-api-firstimage.jpg")
                .put("source_digest", "image-digest-denied")
                .put("reusable", true);
            node.set("source_images", images);
            node.set("external_references", json.createArrayNode());
        });

        CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(List.of(source));
        importer.importManifest(manifest);

        long placeId = placeId(source.sourceItemId());
        assertThat(jdbc.sql(
                """
                SELECT COUNT(*)
                FROM place_source_images image
                JOIN place_source_records record
                  ON record.id = image.place_source_record_id
                WHERE record.place_id = :placeId
                """
            )
            .param("placeId", placeId)
            .query(Long.class)
            .single()).isZero();
    }

    @Test
    void imageOnlyImportPreservesExistingCanonicalPlaceDetails() throws Exception {
        CloudPlaceSourceRow baseline = change(fixtureRows().get(0), node -> {
            node.put("travel_item_id", "task-09-image-only");
            node.put("source_item_id", "task-09-image-only");
            node.put("raw_digest", "task-09-raw-image-only-baseline");
            node.put("description", "preserve this description");
            node.put("public_cell_eligible", true);
            node.set("external_references", json.createArrayNode());
        });
        importer.importManifest(
            manifests.previewImportManifestFixture(List.of(baseline))
        );

        CloudPlaceSourceRow imageUpdate = change(baseline, node -> {
            node.put("raw_digest", "task-09-raw-image-only-update");
            node.put("name", "do not overwrite this name");
            node.set("reusable_fields", json.createArrayNode()
                .add("address")
                .add("image")
                .add("name"));
            ArrayNode images = json.createArrayNode();
            images.addObject()
                .put("source_image_id", "tour-api:firstimage")
                .put("source_url", "https://images.example.test/tour-api-firstimage.jpg")
                .put("source_digest", "image-digest-image-only")
                .put("reusable", true);
            node.set("source_images", images);
        });

        importer.importImages(
            manifests.previewImportManifestFixture(List.of(imageUpdate))
        );

        long placeId = placeId(baseline.sourceItemId());
        assertThat(jdbc.sql("SELECT name FROM places WHERE id = :placeId")
            .param("placeId", placeId)
            .query(String.class)
            .single()).isEqualTo(baseline.name());
        assertThat(jdbc.sql("SELECT description FROM place_details WHERE place_id = :placeId")
            .param("placeId", placeId)
            .query(String.class)
            .single()).isEqualTo("preserve this description");
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM place_source_images image "
                    + "JOIN place_source_records record "
                    + "ON record.id = image.place_source_record_id "
                    + "WHERE record.place_id = :placeId"
            )
            .param("placeId", placeId)
            .query(Long.class)
            .single()).isEqualTo(1);
    }

    @Test
    void identicalManifestRerunIsNoOpAndLeavesCanonicalRowsStable() throws Exception {
        byte[] sourceBefore = Files.readAllBytes(FIXTURE_PATH);
        CloudPlaceSourceRow approved = variant(fixtureRows().get(0), "rerun", "rerun");
        CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(List.of(approved));

        CanonicalPlaceImportResult first = importer.importManifest(manifest);
        long placeId = placeId(approved.sourceItemId());
        String canonicalState = canonicalState(placeId);
        CanonicalPlaceImportResult second = importer.importManifest(manifest);

        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(second).isEqualTo(new CanonicalPlaceImportResult(
            manifest.manifestDigest(), 1, 1, 0, 0, 0, 0, 1, true
        ));
        assertThat(placeCount(approved.sourceItemId())).isEqualTo(1);
        assertThat(canonicalState(placeId)).isEqualTo(canonicalState);
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM catalog_import_runs WHERE manifest_digest = :manifestDigest"
            )
            .param("manifestDigest", manifest.manifestDigest())
            .query(Long.class)
            .single()).isEqualTo(1);
        assertThat(Files.readAllBytes(FIXTURE_PATH)).isEqualTo(sourceBefore);
    }

    @Test
    void changedSourceDigestUpdatesTheExistingCanonicalIdentityExplicitly() throws Exception {
        CloudPlaceSourceRow original = variant(fixtureRows().get(0), "changed", "changed");
        CloudPlaceImportManifest originalManifest = manifests.previewImportManifestFixture(List.of(original));
        importer.importManifest(originalManifest);
        long originalPlaceId = placeId(original.sourceItemId());

        CloudPlaceSourceRow changed = change(original, node -> {
            node.put("raw_digest", "task-09-raw-changed");
            node.put("name", "전주 한옥마을 갱신");
            node.put("description", "changed source digest fixture");
            node.put("source_updated_at", "2026-08-20T12:00:00Z");
        });
        CloudPlaceImportManifest changedManifest = manifests.previewImportManifestFixture(List.of(changed));

        assertThat(changed.rawDigest()).isEqualTo("task-09-raw-changed");
        CanonicalPlaceImportResult result = importer.importManifest(changedManifest);

        assertThat(changedManifest.manifestDigest()).isNotEqualTo(originalManifest.manifestDigest());
        assertThat(result).isEqualTo(new CanonicalPlaceImportResult(
            changedManifest.manifestDigest(), 1, 1, 0, 0, 0, 1, 0, false
        ));
        assertThat(placeCount(changed.sourceItemId())).isEqualTo(1);
        assertThat(placeId(changed.sourceItemId())).isEqualTo(originalPlaceId);
        assertThat(jdbc.sql("SELECT name FROM places WHERE id = :placeId")
            .param("placeId", originalPlaceId)
            .query(String.class)
            .single()).isEqualTo("전주 한옥마을 갱신");
        assertThat(jdbc.sql("SELECT source_digest FROM place_source_records WHERE place_id = :placeId")
            .param("placeId", originalPlaceId)
            .query(String.class)
            .single()).isEqualTo("task-09-raw-changed");
    }

    @Test
    void quarantinedRevokedDuplicateAndInvalidRowsDoNotAffectOtherCanonicalRecords() throws Exception {
        byte[] sourceBefore = Files.readAllBytes(FIXTURE_PATH);
        List<CloudPlaceSourceRow> fixtureRows = fixtureRows();
        CloudPlaceSourceRow primary = variant(fixtureRows.get(0), "isolation-primary", "isolation-primary");
        CloudPlaceSourceRow unaffected = variant(fixtureRows.get(1), "isolation-unaffected", "isolation-unaffected");
        importer.importManifest(manifests.previewImportManifestFixture(List.of(primary, unaffected)));
        long primaryPlaceId = placeId(primary.sourceItemId());
        long unaffectedPlaceId = placeId(unaffected.sourceItemId());

        CloudPlaceSourceRow revoked = change(primary, node -> {
            node.put("raw_digest", "task-09-revoked");
            node.put("license_decision", "REVOKED");
        });
        CloudPlaceSourceRow duplicateOne = duplicateVariant(fixtureRows.get(0), "duplicate-one");
        CloudPlaceSourceRow duplicateTwo = duplicateVariant(fixtureRows.get(0), "duplicate-two");
        CloudPlaceSourceRow invalidDetails = change(
            variant(fixtureRows.get(0), "invalid-details", "invalid-details"),
            node -> node.put("cross_midnight", true)
        );
        CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(List.of(
            revoked,
            unaffected,
            duplicateOne,
            duplicateTwo,
            invalidDetails
        ));

        CanonicalPlaceImportResult result = importer.importManifest(manifest);

        assertThat(result).isEqualTo(new CanonicalPlaceImportResult(
            manifest.manifestDigest(), 5, 1, 4, 0, 0, 0, 1, false
        ));
        assertThat(jdbc.sql("SELECT catalog_status FROM places WHERE id = :placeId")
            .param("placeId", primaryPlaceId)
            .query(String.class)
            .single()).isEqualTo("quarantined");
        assertThat(jdbc.sql("SELECT active FROM place_source_records WHERE place_id = :placeId")
            .param("placeId", primaryPlaceId)
            .query(Boolean.class)
            .single()).isFalse();
        assertThat(jdbc.sql("SELECT catalog_status FROM places WHERE id = :placeId")
            .param("placeId", unaffectedPlaceId)
            .query(String.class)
            .single()).isEqualTo("public");
        assertThat(jdbc.sql("SELECT active FROM place_source_records WHERE place_id = :placeId")
            .param("placeId", unaffectedPlaceId)
            .query(Boolean.class)
            .single()).isTrue();
        assertThat(scalarLong(
            """
            SELECT COUNT(*)
            FROM place_source_records
            WHERE external_id IN ('task-09-duplicate-one', 'task-09-duplicate-two', 'task-09-invalid-details')
            """
        )).isZero();
        assertThat(jdbc.sql(
                """
                SELECT quarantine_reason
                FROM catalog_import_provenance
                WHERE source_travel_item_id IN (
                    :revoked, :duplicateOne, :duplicateTwo, :invalidDetails
                )
                """
            )
            .param("revoked", revoked.travelItemId())
            .param("duplicateOne", duplicateOne.travelItemId())
            .param("duplicateTwo", duplicateTwo.travelItemId())
            .param("invalidDetails", invalidDetails.travelItemId())
            .query(String.class)
            .list()).containsExactlyInAnyOrder(
                "forbidden_reuse_rights",
                "duplicate_external_identity",
                "duplicate_external_identity",
                "invalid_cross_midnight"
            );
        assertThat(Files.readAllBytes(FIXTURE_PATH)).isEqualTo(sourceBefore);
    }

    @Test
    void importerIsOnlyAvailableThroughTheExplicitLocalImportProfile() {
        Profile profile = CanonicalPlaceImporter.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local-import");
    }

    private List<CloudPlaceSourceRow> fixtureRows() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream("/compat/cloud-place-fixture.json")) {
            assertThat(fixture).isNotNull();
            return json.readValue(fixture, new TypeReference<>() {});
        }
    }

    private CloudPlaceSourceRow variant(CloudPlaceSourceRow source, String suffix, String externalSuffix) {
        return change(source, node -> {
            node.put("travel_item_id", "task-09-" + suffix);
            node.put("source_item_id", "task-09-" + externalSuffix);
            node.put("raw_digest", "task-09-raw-" + suffix);
            node.set("external_references", json.createArrayNode());
        });
    }

    private CloudPlaceSourceRow duplicateVariant(CloudPlaceSourceRow source, String suffix) {
        return change(source, node -> {
            node.put("travel_item_id", "task-09-" + suffix);
            node.put("source_item_id", "task-09-" + suffix);
            node.put("raw_digest", "task-09-raw-" + suffix);
            ArrayNode references = json.createArrayNode();
            references.addObject()
                .put("provider", "KAKAO")
                .put("external_id", "task-09-shared-duplicate");
            node.set("external_references", references);
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

    private long placeId(String externalId) {
        return jdbc.sql(
                "SELECT id FROM places WHERE source = 'public_data' AND external_id = :externalId"
            )
            .param("externalId", externalId)
            .query(Long.class)
            .single();
    }

    private long placeCount(String externalId) {
        return jdbc.sql(
                "SELECT COUNT(*) FROM places WHERE source = 'public_data' AND external_id = :externalId"
            )
            .param("externalId", externalId)
            .query(Long.class)
            .single();
    }

    private String canonicalState(long placeId) {
        return jdbc.sql(
                """
                SELECT place.catalog_status || '|' || record.source_digest || '|' || details.description
                FROM places place
                JOIN place_source_records record ON record.place_id = place.id
                JOIN place_details details ON details.place_id = place.id
                WHERE place.id = :placeId
                """
            )
            .param("placeId", placeId)
            .query(String.class)
            .single();
    }

    private long scalarLong(String sql, long placeId) {
        return jdbc.sql(sql)
            .param("placeId", placeId)
            .query(Long.class)
            .single();
    }


    @TestConfiguration(proxyBeanMethods = false)
    static class ImporterTestConfiguration {
        @Bean
        CanonicalPlaceImporter canonicalPlaceImporter(
            JdbcClient jdbc,
            DataSource dataSource,
            PlaceSearchNormalizer names
        ) {
            return new CanonicalPlaceImporter(jdbc, dataSource, names);
        }
    }

    private long scalarLong(String sql) {
        return jdbc.sql(sql)
            .query(Long.class)
            .single();
    }
}
