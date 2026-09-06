package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CanonicalDocsContractTest {
    private static final Yaml YAML = new Yaml();
    private static Path root;
    private static Map<String, Object> glossary;
    private static Map<String, Object> product;
    private static Map<String, Object> architecture;
    private static Map<String, Object> conventions;
    private static Map<String, Object> pitfalls;
    private static Map<String, Object> changelog;

    @BeforeAll
    static void loadCanonicalDocuments() throws IOException {
        root = findRoot();
        glossary = parse("docs/glossary.yaml");
        product = parse("docs/product.yaml");
        architecture = parse("docs/architecture.yaml");
        conventions = parse("docs/conventions.yaml");
        pitfalls = parse("docs/pitfalls.yaml");
        changelog = parse("CHANGELOG.yaml");
    }

    @Test
    void canonicalYamlDocumentsAreParsed() {
        assertThat(List.of(glossary, product, architecture, conventions, pitfalls, changelog))
            .allSatisfy(document -> assertThat(document).isNotEmpty());
        assertThat(glossary).containsKeys("meta", "terms");
        assertThat(product).containsKeys("features", "tuning_parameters", "deferred");
        assertThat(architecture).containsKeys("database_schema", "api", "feature_contracts");
        assertThat(conventions).containsKeys("source_of_truth", "tuning");
        assertThat(pitfalls).containsKeys("common", "server", "db");
        assertThat(changelog).containsKey("changes");
    }

    @Test
    void catalogContractHasLicensedPublicBoundaryAndLexicalFields() {
        Map<String, Object> tables = tablesByName();
        assertThat(tables).containsKeys(
            "catalog_sources",
            "license_snapshots",
            "place_source_records",
            "place_source_images",
            "place_aliases",
            "places"
        );
        assertThat(map(map(tables.get("places")).get("enums")))
            .containsKey("catalog_status");
        assertThat(map(map(tables.get("places")).get("enums")).get("catalog_status"))
            .isEqualTo(List.of("private_reference", "public", "quarantined"));
        assertThat(list(map(tables.get("places")).get("columns")))
            .contains("normalized_name", "compact_name");
        assertThat(map(tables.get("places")))
            .containsKey("public_eligibility");
        assertThat(map(map(tables.get("places")).get("public_eligibility")).get("all_of"))
            .isEqualTo(List.of(
                "catalog_status=public",
                "public_cell_eligible",
                "active_place_source_record",
                "applicable_license_snapshot"
            ));
        assertThat(list(map(architecture.get("search")).get("indexes")))
            .anyMatch(index -> index.toString().contains("pg_trgm"));
    }

    @Test
    void featureIdsCrossReferenceRelevantSchemaAndApiRules() {
        Map<String, Object> contracts = map(architecture.get("feature_contracts"));
        assertThat(contracts).containsKeys(
            "F-02", "F-04", "F-07", "F-10", "F-11", "F-12", "F-15", "F-17", "F-25"
        );

        assertContract(contracts, "F-02", "cell_stats", "GET /cells");
        assertContract(contracts, "F-04", "photos", "POST /photos");
        assertContract(contracts, "F-07", "photos", "GET /trips/{tripId}/photos");
        assertContract(contracts, "F-10", "places", "POST /places/search");
        assertContract(contracts, "F-11", "visits", "POST /trips/{id}/visits");
        assertContract(contracts, "F-12", "likes", "GET /feed");
        assertContract(contracts, "F-15", "trip_members", "DELETE /trips/{id}/members/me");
        assertContract(contracts, "F-17", "events", "POST /events/nearby");
        assertContract(contracts, "F-25", "photo_public_grants", "PATCH /photos/{id}/moderation");
    }

    @Test
    void photoSocialAndCellContractsExposeMachineFields() {
        Map<String, Object> tables = tablesByName();
        assertThat(tables).containsKeys(
            "photo_public_grants",
            "photo_moderation_events",
            "likes",
            "cell_stats"
        );
        Map<String, Object> photos = map(tables.get("photos"));
        assertThat(map(photos.get("enums")).get("moderation_status"))
            .isEqualTo(List.of("pending", "approved", "blocked"));
        assertThat(list(map(tables.get("cell_stats")).get("columns")))
            .contains("landmark_count", "public_photo_count", "public_photo_like_count", "top_photo_id");
        assertThat(map(tables.get("cell_stats"))).containsEntry("rebuildable", true);

        Map<String, Object> visibility = map(architecture.get("visibility_policy"));
        List<Object> eligibility = list(map(visibility.get("public_photo_eligibility")).get("all_of"));
        assertThat(eligibility).containsExactly(
            "trip.visibility=public",
            "photo.visibility=public"
        );
        assertThat(publicPhotoEligible(true, true, false)).isTrue();
        assertThat(publicPhotoEligible(false, true, false)).isFalse();
        assertThat(publicPhotoEligible(true, true, true)).isFalse();
    }

    @Test
    void deferredSearchContractsRejectEnabledFixtures() {
        Map<String, Object> search = map(architecture.get("search"));
        assertThat(search.get("mode")).isEqualTo("local_first_lexical");
        assertThat(list(search.get("deferred")))
            .contains("embedding", "pgvector", "semantic_search", "image_similarity");

        List<Object> deferred = list(map(architecture.get("deferred_contracts")).get("items"));
        Map<String, Object> embedding = deferredItem(deferred, "embedding");
        assertThat(embedding.get("status")).isEqualTo("deferred");
        assertThat(embedding.get("reason"))
            .isEqualTo("representative quality, cost, and region pilot not selected");

        assertThat(acceptsPhaseOneFixture(deferred, Map.of("id", "pgvector", "status", "enabled"))).isFalse();
        assertThat(deferred).noneMatch(item -> "events".equals(map(item).get("id")));
        assertThat(tablesByName()).containsKey("events");
    }

    @Test
    void p0P1DeliveryContractsLockExecutionAndCatalogBoundaries() {
        Map<String, Object> delivery = map(map(product.get("delivery_contracts")).get("p0_p1"));
        assertThat(delivery).containsEntry("source", "user");

        Map<String, Object> memberCollection = map(delivery.get("member_collection"));
        assertThat(memberCollection)
            .containsEntry("scope", "per_member_device")
            .containsEntry("state_owner", "trip_member_collection_states")
            .containsEntry("visit_ownership", "member")
            .containsEntry("failure_isolation", "per_member");

        Map<String, Object> immutableWrites = map(delivery.get("immutable_writes"));
        assertImmutableWrite(map(immutableWrites.get("visit")), "client_visit_id");
        assertImmutableWrite(map(immutableWrites.get("basket_item")), "client_item_id");

        Map<String, Object> lateVisit = map(delivery.get("late_visit"));
        assertThat(lateVisit)
            .containsEntry("grace_parameter", "late_visit_grace_hours")
            .containsEntry("accepted_if", "observed_interval_end_lte_trip_ended_at")
            .containsEntry("committed_replay_after_end", "acknowledged");
        assertThat(tuningParameter("late_visit_grace_hours").get("initial")).isEqualTo(24);
        assertThat(tuningParameter("place_merge_distance_m").get("initial")).isEqualTo(50);

        Map<String, Object> shareImport = map(delivery.get("share_import"));
        assertThat(shareImport)
            .containsEntry("raw_storage", "android_local_only")
            .containsEntry("cloud_sync", "confirmed_or_manual_place_cards_only")
            .containsEntry("candidate_cardinality", "zero_to_many")
            .containsEntry("cleanup", "after_all_accepted_writes_acknowledged");
        assertThat(list(shareImport.get("candidate_origin_states")))
            .containsExactly("extracted", "corrected", "manual");
        assertThat(list(shareImport.get("candidate_decision_states")))
            .containsExactly("pending", "confirmed", "rejected");

        Map<String, Object> catalog = map(delivery.get("catalog"));
        assertThat(acceptsP0P1CatalogContract(catalog)).isTrue();
        assertThat(acceptsP0P1CatalogContract(Map.of(
            "phase", "P0_P1",
            "current_cleaned_tourism_catalog", "preserved"
        ))).isFalse();
        assertThat(list(catalog.get("preserved_tables"))).contains(
            "places", "place_details", "catalog_sources", "license_snapshots",
            "place_source_records", "place_source_images"
        );
        assertThat(map(catalog.get("forward_migrations")))
            .containsEntry("feature_required", "named_gates_only")
            .containsEntry("v17_preference_traits_constraint", "forward_fix_at_todo_4")
            .containsEntry("v10_restore_safety", "forward_fix_at_todo_4")
            .containsEntry("applied_v1_v17_edits", "forbidden");

        Map<String, Object> execution = map(map(architecture.get("execution_boundaries")).get("p0_p1"));
        assertThat(execution)
            .containsEntry("backend_process", "local")
            .containsEntry("runtime_profiles", "prod,gcs-write")
            .containsEntry("database", "Cloud SQL stog_canonical")
            .containsEntry("object_storage", "GCS")
            .containsEntry("protected_legacy_profile", "cloud")
            .containsEntry("protected_legacy_database", "Cloud SQL stog_0");
        assertThat(map(execution.get("canonical_cleaning")))
            .containsEntry("owner", "agent")
            .containsEntry("status", "complete")
            .containsEntry("exclusive_plan", ".omo/plans/stog-canonical-db-cleaning.md");

        Map<String, Object> deviceTesting = map(map(conventions.get("verification_boundaries")).get("device_testing"));
        assertThat(deviceTesting)
            .containsEntry("executor", "owner_only")
            .containsEntry("agent_execution", "forbidden")
            .containsEntry("agent_device_claims", "forbidden");
    }

    private static void assertContract(
        Map<String, Object> contracts,
        String featureId,
        String schemaName,
        String endpoint
    ) {
        Map<String, Object> contract = map(contracts.get(featureId));
        assertThat(list(contract.get("schema"))).contains(schemaName);
        assertThat(list(contract.get("api"))).contains(endpoint);
    }

    private static void assertImmutableWrite(Map<String, Object> contract, String key) {
        assertThat(contract)
            .containsEntry("key", key)
            .containsEntry("payload_fingerprint", "required_immutable")
            .containsEntry("same_payload_replay", "return_original_response")
            .containsEntry("changed_payload_replay", "conflict_409");
        assertThat(list(contract.get("scope"))).containsExactly("trip_id", "user_id");
    }

    private static Map<String, Object> tuningParameter(String key) {
        return list(map(product.get("tuning_parameters")).get("items")).stream()
            .map(CanonicalDocsContractTest::map)
            .filter(item -> key.equals(item.get("key")))
            .findFirst()
            .orElseThrow();
    }

    private static boolean acceptsP0P1CatalogContract(Map<String, Object> contract) {
        return "P0_P1".equals(contract.get("phase"))
            && "preserved".equals(contract.get("current_cleaned_tourism_catalog"))
            && "local_first_with_google_places_fallback".equals(contract.get("search"))
            && "deferred_after_P0_P1".equals(contract.get("storage_redesign"))
            && "forbidden".equals(contract.get("google_fallback_bulk_canonical_persistence"));
    }

    private static boolean publicPhotoEligible(
        boolean publicTrip,
        boolean publicPhoto,
        boolean blocked
    ) {
        return publicTrip && publicPhoto && !blocked;
    }

    private static boolean acceptsPhaseOneFixture(List<Object> deferred, Map<String, Object> fixture) {
        return "deferred".equals(fixture.get("status")) && deferredItem(deferred, fixture.get("id").toString()) != null;
    }

    private static Map<String, Object> deferredItem(List<Object> items, String id) {
        return items.stream()
            .map(CanonicalDocsContractTest::map)
            .filter(item -> id.equals(item.get("id")))
            .findFirst()
            .orElseThrow();
    }

    private static Map<String, Object> tablesByName() {
        return list(map(architecture.get("database_schema")).get("tables")).stream()
            .map(CanonicalDocsContractTest::map)
            .collect(java.util.stream.Collectors.toMap(item -> item.get("name").toString(), item -> item));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> parse(String relativePath) throws IOException {
        Object document = YAML.load(read(relativePath));
        assertThat(document).isInstanceOf(Map.class);
        return map(document);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("docs/glossary.yaml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("STOG repository root was not found");
        }
        return current;
    }
}
