package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelGuideAiSerializationTest {
    @Test
    void serializesTripVersionOrderedActionsFixedIdsAndExclusions() throws Exception {
        // Given
        TravelGuideAiResponses.Preview preview = new TravelGuideAiResponses.Preview(
            UUID.fromString("00000000-0000-0000-0000-000000000014"),
            7,
            3,
            "ready",
            "a".repeat(64),
            true,
            List.of(
                action(0, 41, 0, true),
                action(1, 42, 1, false)
            ),
            List.of(41L),
            List.of(99L),
            List.of()
        );

        // When
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(preview));

        // Then
        assertThat(json.get("suggestion_id").asText())
            .isEqualTo("00000000-0000-0000-0000-000000000014");
        assertThat(json.get("trip_id").asLong()).isEqualTo(7);
        assertThat(json.get("base_version").asLong()).isEqualTo(3);
        assertThat(json.get("actions").get(0).get("basket_item_id").asLong()).isEqualTo(41);
        assertThat(json.get("actions").get(1).get("basket_item_id").asLong()).isEqualTo(42);
        assertThat(json.get("fixed_basket_item_ids").get(0).asLong()).isEqualTo(41);
        assertThat(json.get("excluded_unresolved_basket_item_ids").get(0).asLong()).isEqualTo(99);
    }

    private static TravelGuideAiResponses.Action action(
        int actionOrder,
        long basketItemId,
        int orderIndex,
        boolean fixed
    ) {
        return new TravelGuideAiResponses.Action(
            actionOrder, "set_itinerary_item", basketItemId, 1, orderIndex,
            orderIndex == 0 ? "09:00" : "10:30", 60, orderIndex == 0 ? 0 : 30, fixed
        );
    }
}
