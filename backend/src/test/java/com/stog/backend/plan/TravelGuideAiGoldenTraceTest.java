package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TravelGuideAiGoldenTraceTest {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
        "trace_id", "schema_version", "base_version", "proposal_fingerprint",
        "client_apply_id", "given", "when", "expected"
    );
    private static final Set<String> GIVEN_FIELDS = Set.of(
        "trip_id", "actor_user_id", "actor_membership", "itinerary_version",
        "replacement_candidate", "declared_schedule_item_count", "current_schedule",
        "before_counts", "before_state"
    );
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
        "basket_item_id", "trip_id", "confirmation_state", "resolution"
    );
    private static final Set<String> WHEN_FIELDS = Set.of(
        "operation", "request_trip_id", "request_base_version", "selected_target_basket_item_id",
        "replacement_candidate_basket_item_id", "candidate_resolution", "schedule_input",
        "provider_result", "model_result", "response_delivery"
    );
    private static final Set<String> EXPECTED_FIELDS = Set.of(
        "outcome", "error_code", "server_write", "itinerary_persisted", "schedule_after",
        "retained_basket_item_ids", "removed_basket_item_ids", "added_basket_item_ids",
        "after_counts", "after_state", "apply_receipt"
    );
    private static final Set<String> COUNT_FIELDS = Set.of(
        "itinerary_items", "itinerary_changes", "proposals", "proposal_actions", "apply_receipts"
    );
    private static final Set<String> STATE_FIELDS = Set.of(
        "proposal", "actions", "apply_receipt"
    );
    private static final Set<String> SCHEDULE_ITEM_FIELDS = Set.of(
        "basket_item_id", "day_number", "order_index", "planned_arrival",
        "planned_duration_min", "is_fixed", "role"
    );
    private static final Set<String> REQUIRED_OUTCOMES = Set.of(
        "PROPOSAL_READY",
        "APPLIED",
        "DISMISSED_LOCAL",
        "REJECTED_STALE_VERSION",
        "REJECTED_FORBIDDEN",
        "REJECTED_CROSS_TRIP_TARGET",
        "REJECTED_CROSS_TRIP_CANDIDATE",
        "REJECTED_TARGET_MISSING",
        "REJECTED_TARGET_DUPLICATE",
        "REJECTED_CANDIDATE_UNRESOLVED",
        "REJECTED_SCHEDULE_EMPTY",
        "REJECTED_SCHEDULE_INCOMPLETE",
        "PROVIDER_FAILURE",
        "MODEL_FAILURE",
        "AMBIGUOUS_TIMEOUT"
    );
    private static final Set<String> NON_FAILURE_OUTCOMES = Set.of(
        "PROPOSAL_READY", "APPLIED", "DISMISSED_LOCAL"
    );

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesCompleteMachineTracesWithUniqueIdsAndDeterministicOrdering() throws Exception {
        List<GoldenTrace> traces = loadTraces();
        Set<String> traceIds = new HashSet<>();
        Set<String> outcomes = new HashSet<>();

        for (GoldenTrace trace : traces) {
            validateTrace(trace.root());
            assertThat(traceIds.add(trace.root().path("trace_id").asText())).isTrue();
            outcomes.add(trace.root().at("/expected/outcome").asText());
        }

        assertThat(traces).hasSize(REQUIRED_OUTCOMES.size());
        assertThat(outcomes).isEqualTo(REQUIRED_OUTCOMES);
    }

    @Test
    void completeReplacementRetainsEveryUnrelatedItemAndApplyHasSeparateReceipt() throws Exception {
        Map<String, JsonNode> traces = tracesByOutcome();
        JsonNode preview = traces.get("PROPOSAL_READY");
        JsonNode apply = traces.get("APPLIED");

        assertThat(ids(preview.at("/expected/schedule_after")))
            .containsExactly(1101L, 2102L, 1103L, 1201L, 1202L);
        assertThat(ids(preview.at("/expected/retained_basket_item_ids")))
            .containsExactly(1101L, 1103L, 1201L, 1202L);
        assertThat(preview.at("/expected/removed_basket_item_ids/0").asLong()).isEqualTo(1102L);
        assertThat(preview.at("/expected/added_basket_item_ids/0").asLong()).isEqualTo(2102L);
        assertThat(preview.at("/expected/after_state/proposal").asText()).isEqualTo("ready");
        assertThat(preview.at("/expected/after_state/actions").asText())
            .isEqualTo("complete_snapshot");
        assertThat(preview.at("/expected/after_state/apply_receipt").asText()).isEqualTo("absent");
        assertThat(preview.at("/expected/itinerary_persisted").asBoolean()).isFalse();

        assertThat(apply.path("proposal_fingerprint").asText())
            .isEqualTo(preview.path("proposal_fingerprint").asText());
        assertThat(apply.path("client_apply_id").asText())
            .isEqualTo(preview.path("client_apply_id").asText());
        assertThat(apply.at("/given/before_state/proposal").asText()).isEqualTo("ready");
        assertThat(apply.at("/expected/after_state/proposal").asText()).isEqualTo("applied");
        assertThat(apply.at("/expected/after_state/apply_receipt").asText()).isEqualTo("recorded");
        assertThat(apply.at("/expected/apply_receipt/proposal_fingerprint").asText())
            .isEqualTo(apply.path("proposal_fingerprint").asText());
        assertThat(apply.at("/expected/apply_receipt/client_apply_id").asText())
            .isEqualTo(apply.path("client_apply_id").asText());
        assertThat(apply.at("/expected/after_counts/itinerary_changes").asLong())
            .isEqualTo(apply.at("/given/before_counts/itinerary_changes").asLong() + 1);
        assertThat(apply.at("/expected/after_counts/apply_receipts").asLong())
            .isEqualTo(apply.at("/given/before_counts/apply_receipts").asLong() + 1);
    }

    @Test
    void dismissalAndEveryFailureKeepItineraryHistoryAndApplyReceiptUnchanged() throws Exception {
        for (GoldenTrace trace : loadTraces()) {
            JsonNode root = trace.root();
            String outcome = root.at("/expected/outcome").asText();
            if (Set.of("PROPOSAL_READY", "APPLIED").contains(outcome)) {
                continue;
            }
            JsonNode beforeCounts = root.at("/given/before_counts");
            JsonNode afterCounts = root.at("/expected/after_counts");
            JsonNode beforeState = root.at("/given/before_state");
            JsonNode afterState = root.at("/expected/after_state");

            assertThat(afterCounts.path("itinerary_items").asLong())
                .as(trace.path() + " itinerary_items")
                .isEqualTo(beforeCounts.path("itinerary_items").asLong());
            assertThat(afterCounts.path("itinerary_changes").asLong())
                .as(trace.path() + " itinerary_changes")
                .isEqualTo(beforeCounts.path("itinerary_changes").asLong());
            assertThat(afterCounts.path("proposals").asLong())
                .as(trace.path() + " proposals")
                .isEqualTo(beforeCounts.path("proposals").asLong());
            assertThat(afterCounts.path("proposal_actions").asLong())
                .as(trace.path() + " proposal_actions")
                .isEqualTo(beforeCounts.path("proposal_actions").asLong());
            assertThat(afterCounts.path("apply_receipts").asLong())
                .as(trace.path() + " apply_receipts")
                .isEqualTo(beforeCounts.path("apply_receipts").asLong());
            assertThat(afterState).isEqualTo(beforeState);
            assertThat(root.at("/expected/server_write").asText()).isEqualTo("none");
            assertThat(root.at("/expected/itinerary_persisted").asBoolean()).isFalse();
        }
    }

    @Test
    void malformedCopiesAreRejectedBeforeRepositoryWrite() throws Exception {
        JsonNode valid = tracesByOutcome().get("PROPOSAL_READY");
        AtomicInteger repositoryWrites = new AtomicInteger();

        ObjectNode omittedUnrelatedItem = valid.deepCopy();
        omittedUnrelatedItem.withObject("/expected").withArray("schedule_after").remove(2);
        assertThatThrownBy(() -> validateBeforeRepositoryWrite(
            omittedUnrelatedItem, repositoryWrites::incrementAndGet
        )).isInstanceOf(AssertionError.class);

        ObjectNode conflatedIdentity = valid.deepCopy();
        conflatedIdentity.put("client_apply_id", valid.path("proposal_fingerprint").asText());
        assertThatThrownBy(() -> validateBeforeRepositoryWrite(
            conflatedIdentity, repositoryWrites::incrementAndGet
        )).isInstanceOf(AssertionError.class);

        ObjectNode mismatchedCandidate = valid.deepCopy();
        mismatchedCandidate.withObject("/when").put("replacement_candidate_basket_item_id", 9202);
        assertThatThrownBy(() -> validateBeforeRepositoryWrite(
            mismatchedCandidate, repositoryWrites::incrementAndGet
        )).isInstanceOf(AssertionError.class);

        ObjectNode unconfirmedCandidate = valid.deepCopy();
        unconfirmedCandidate.withObject("/given/replacement_candidate")
            .put("confirmation_state", "unconfirmed");
        assertThatThrownBy(() -> validateBeforeRepositoryWrite(
            unconfirmedCandidate, repositoryWrites::incrementAndGet
        )).isInstanceOf(AssertionError.class);

        ObjectNode wrongRequestTrip = valid.deepCopy();
        wrongRequestTrip.withObject("/when").put("request_trip_id", 5999);
        assertThatThrownBy(() -> validateBeforeRepositoryWrite(
            wrongRequestTrip, repositoryWrites::incrementAndGet
        )).isInstanceOf(AssertionError.class);

        assertThat(repositoryWrites).hasValue(0);
    }

    private void validateBeforeRepositoryWrite(JsonNode root, Runnable repositoryWrite) {
        validateTrace(root);
        repositoryWrite.run();
    }

    private void validateTrace(JsonNode root) {
        assertExactFields(root, TOP_LEVEL_FIELDS);
        assertThat(root.path("trace_id").asText()).matches("stobee-ai-[0-9]{2}-[a-z0-9-]+");
        assertThat(root.path("schema_version").asText()).isEqualTo("stobee-ai-golden-trace/v1");
        assertThat(root.path("base_version").isIntegralNumber()).isTrue();
        assertThat(root.path("base_version").asLong()).isNotNegative();
        assertThat(root.path("proposal_fingerprint").asText()).matches("[0-9a-f]{64}");
        assertThatCodeIsUuid(root.path("client_apply_id").asText());
        assertIdentityDimensionsAreSeparate(root);

        JsonNode given = root.path("given");
        JsonNode when = root.path("when");
        JsonNode expected = root.path("expected");
        JsonNode candidate = given.path("replacement_candidate");
        assertExactFields(given, GIVEN_FIELDS);
        assertExactFields(candidate, CANDIDATE_FIELDS);
        assertExactFields(when, WHEN_FIELDS);
        assertExactFields(expected, EXPECTED_FIELDS);
        assertExactFields(given.path("before_counts"), COUNT_FIELDS);
        assertExactFields(expected.path("after_counts"), COUNT_FIELDS);
        assertExactFields(given.path("before_state"), STATE_FIELDS);
        assertExactFields(expected.path("after_state"), STATE_FIELDS);
        assertNonNegativeCounts(given.path("before_counts"));
        assertNonNegativeCounts(expected.path("after_counts"));

        assertThat(given.path("trip_id").asLong()).isPositive();
        assertThat(given.path("actor_user_id").asLong()).isPositive();
        assertThat(given.path("itinerary_version").asLong()).isNotNegative();
        assertThat(given.path("declared_schedule_item_count").asInt()).isNotNegative();
        assertThat(when.path("request_trip_id").asLong()).isPositive();
        assertThat(when.path("request_trip_id").asLong()).isEqualTo(given.path("trip_id").asLong());
        assertThat(when.path("request_base_version").asLong()).isNotNegative();
        String outcome = expected.path("outcome").asText();
        assertThat(outcome).isIn(REQUIRED_OUTCOMES);
        assertThat(expected.path("server_write").asText())
            .isIn("none", "proposal_only", "apply_transaction");
        assertCandidateBoundary(candidate, when, expected, outcome);

        assertOrderedSchedule(given.path("current_schedule"));
        assertOrderedSchedule(when.path("schedule_input"));
        assertOrderedSchedule(expected.path("schedule_after"));
        assertThat(given.path("current_schedule").size())
            .isEqualTo(given.at("/before_counts/itinerary_items").asInt());
        assertThat(expected.path("schedule_after").size())
            .isEqualTo(expected.at("/after_counts/itinerary_items").asInt());

        if (!"PROPOSAL_READY".equals(outcome)) {
            assertThat(expected.path("schedule_after")).isEqualTo(
                "APPLIED".equals(outcome)
                    ? when.path("schedule_input")
                    : given.path("current_schedule")
            );
        }
        if ("APPLIED".equals(outcome)) {
            assertExactFields(expected.path("apply_receipt"), Set.of(
                "proposal_fingerprint", "client_apply_id", "itinerary_change_id", "itinerary_version"
            ));
            assertThat(expected.at("/apply_receipt/proposal_fingerprint").asText())
                .isEqualTo(root.path("proposal_fingerprint").asText());
            assertThat(expected.at("/apply_receipt/client_apply_id").asText())
                .isEqualTo(root.path("client_apply_id").asText());
        } else {
            assertThat(expected.path("apply_receipt").isNull()).isTrue();
        }
        if (!NON_FAILURE_OUTCOMES.contains(outcome)) {
            assertThat(expected.path("error_code").isTextual()).isTrue();
        }
    }

    private void assertCandidateBoundary(
        JsonNode candidate,
        JsonNode when,
        JsonNode expected,
        String outcome
    ) {
        long candidateId = candidate.path("basket_item_id").asLong();
        long candidateTripId = candidate.path("trip_id").asLong();
        long requestTripId = when.path("request_trip_id").asLong();
        String confirmation = candidate.path("confirmation_state").asText();
        String resolution = candidate.path("resolution").asText();

        assertThat(candidateId).isPositive();
        assertThat(candidateTripId).isPositive();
        assertThat(candidateId)
            .isEqualTo(when.path("replacement_candidate_basket_item_id").asLong());
        assertThat(resolution).isEqualTo(when.path("candidate_resolution").asText());
        assertThat(confirmation).isIn("confirmed", "unconfirmed");
        assertThat(resolution).isIn("resolved", "manual", "unresolved");

        if ("REJECTED_CROSS_TRIP_CANDIDATE".equals(outcome)) {
            assertThat(candidateTripId).isNotEqualTo(requestTripId);
        } else {
            assertThat(candidateTripId).isEqualTo(requestTripId);
        }
        if ("REJECTED_CANDIDATE_UNRESOLVED".equals(outcome)) {
            assertThat(confirmation).isEqualTo("unconfirmed");
            assertThat(resolution).isEqualTo("unresolved");
        } else {
            assertThat(confirmation).isEqualTo("confirmed");
            assertThat(resolution).isIn("resolved", "manual");
        }
        if (Set.of("PROPOSAL_READY", "APPLIED").contains(outcome)) {
            assertThat(ids(expected.path("added_basket_item_ids"))).containsExactly(candidateId);
            assertThat(expected.path("schedule_after").findValuesAsText("role"))
                .contains("replacement");
        }
    }

    private void assertIdentityDimensionsAreSeparate(JsonNode root) {
        List<String> identities = List.of(
            root.path("schema_version").asText(),
            root.path("base_version").asText(),
            root.path("proposal_fingerprint").asText(),
            root.path("client_apply_id").asText()
        );
        assertThat(new HashSet<>(identities)).hasSameSizeAs(identities);
    }

    private void assertNonNegativeCounts(JsonNode counts) {
        for (String field : COUNT_FIELDS) {
            assertThat(counts.path(field).isIntegralNumber()).isTrue();
            assertThat(counts.path(field).asLong()).isNotNegative();
        }
    }

    private void assertOrderedSchedule(JsonNode schedule) {
        assertThat(schedule.isArray()).isTrue();
        int previousDay = -1;
        int previousOrder = -1;
        Set<String> positions = new HashSet<>();
        for (JsonNode item : schedule) {
            assertExactFields(item, SCHEDULE_ITEM_FIELDS);
            int day = item.path("day_number").asInt();
            int order = item.path("order_index").asInt();
            assertThat(day).isPositive();
            assertThat(order).isNotNegative();
            assertThat(day > previousDay || day == previousDay && order > previousOrder).isTrue();
            assertThat(positions.add(day + ":" + order)).isTrue();
            assertThat(item.path("basket_item_id").asLong()).isPositive();
            assertThat(item.path("planned_arrival").asText()).matches("[0-2][0-9]:[0-5][0-9]");
            assertThat(item.path("planned_duration_min").asInt()).isPositive();
            assertThat(item.path("is_fixed").isBoolean()).isTrue();
            assertThat(item.path("role").asText())
                .isIn("fixed", "target", "replacement", "unrelated");
            previousDay = day;
            previousOrder = order;
        }
    }

    private static void assertExactFields(JsonNode node, Set<String> expected) {
        assertThat(node.isObject()).isTrue();
        assertThat(fieldNames(node)).isEqualTo(expected);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(fields::add);
        return fields;
    }

    private static void assertThatCodeIsUuid(String value) {
        assertThatThrownBy(() -> {
            UUID.fromString(value);
            throw new ValidUuidMarker();
        }).isInstanceOf(ValidUuidMarker.class);
    }

    private Map<String, JsonNode> tracesByOutcome() throws Exception {
        Map<String, JsonNode> byOutcome = new LinkedHashMap<>();
        for (GoldenTrace trace : loadTraces()) {
            validateTrace(trace.root());
            byOutcome.put(trace.root().at("/expected/outcome").asText(), trace.root());
        }
        return byOutcome;
    }

    private List<GoldenTrace> loadTraces() throws IOException {
        Path goldenDirectory = repositoryRoot().resolve("docs/contracts/stobee-ai/golden");
        assertThat(Files.isDirectory(goldenDirectory)).isTrue();
        List<GoldenTrace> traces = new ArrayList<>();
        try (Stream<Path> paths = Files.list(goldenDirectory)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".json"))
                .sorted().toList()) {
                traces.add(new GoldenTrace(path, json.readTree(Files.readAllBytes(path))));
            }
        }
        return traces;
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.isObject() ? node.path("basket_item_id").asLong() : node.asLong());
        }
        return values;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("docs/architecture.yaml"))
                && Files.isRegularFile(candidate.resolve("backend/build.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository root was not found");
    }

    private record GoldenTrace(Path path, JsonNode root) {
    }

    private static final class ValidUuidMarker extends RuntimeException {
    }
}
