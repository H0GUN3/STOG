package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TravelGuideAiProposalHttpIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewExcludesUnresolvedAndWritesNoItineraryBeforeExplicitReplaySafeApply() throws Exception {
        // Given
        TravelGuideAiTestFixture.Fixture fixture = fixtures().create();
        long otherTrip = fixtures().trip(fixture.ownerId(), "다른 여행");
        String previewBody = TravelGuideAiTestFixture.previewBody(fixture);
        long itineraryBefore = fixtures().count("itinerary_items", fixture.tripId());
        long changesBefore = fixtures().count("itinerary_changes", fixture.tripId());

        // When
        MvcResult previewResult = mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions", fixture.tripId()
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(previewBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trip_id").value(fixture.tripId()))
            .andExpect(jsonPath("$.base_version").value(0))
            .andExpect(jsonPath("$.feasible").value(true))
            .andExpect(jsonPath("$.actions[0].basket_item_id").value(fixture.fixedItemId()))
            .andExpect(jsonPath("$.actions[0].is_fixed").value(true))
            .andExpect(jsonPath("$.actions[1].basket_item_id").value(fixture.resolvedItemId()))
            .andExpect(jsonPath("$.excluded_unresolved_basket_item_ids[0]")
                .value(fixture.unresolvedItemId()))
            .andReturn();

        // Then
        assertThat(fixtures().count("itinerary_items", fixture.tripId())).isEqualTo(itineraryBefore);
        assertThat(fixtures().count("itinerary_changes", fixture.tripId())).isEqualTo(changesBefore);
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsByteArray());
        String suggestionId = preview.get("suggestion_id").asText();
        String fingerprint = preview.get("proposal_fingerprint").asText();
        String applyId = UUID.randomUUID().toString();
        String applyBody = TravelGuideAiTestFixture.applyBody(applyId, fingerprint);

        mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                otherTrip, suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROPOSAL_WRONG_TRIP"));

        MvcResult firstApply = mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                fixture.tripId(), suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("applied"))
            .andExpect(jsonPath("$.itinerary_version").value(1))
            .andExpect(jsonPath("$.items[0].basket_item_id").value(fixture.fixedItemId()))
            .andExpect(jsonPath("$.items[0].planned_arrival").value("09:00"))
            .andExpect(jsonPath("$.items[0].is_fixed").value(true))
            .andReturn();
        MvcResult replay = mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                fixture.tripId(), suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(replay.getResponse().getContentAsString())
            .isEqualTo(firstApply.getResponse().getContentAsString());
        assertThat(fixtures().count("itinerary_changes", fixture.tripId())).isEqualTo(changesBefore + 1);
        assertThat(jdbc.sql(
                "SELECT action FROM itinerary_changes WHERE trip_id = :tripId"
            )
            .param("tripId", fixture.tripId())
            .query(String.class)
            .single()).isEqualTo("replace");

        mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                fixture.tripId(), suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TravelGuideAiTestFixture.applyBody(UUID.randomUUID().toString(), fingerprint)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROPOSAL_REPLAY_CONFLICT"));
        mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                fixture.tripId(), suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TravelGuideAiTestFixture.applyBody(applyId, "b".repeat(64))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROPOSAL_REPLAY_CONFLICT"));
    }

    @Test
    void staleVersionAndNonmemberAreRejectedWithoutApplying() throws Exception {
        // Given
        TravelGuideAiTestFixture.Fixture fixture = fixtures().create();
        long nonmember = fixtures().user("nonmember");
        MvcResult previewResult = preview(fixture, fixture.ownerId());
        JsonNode preview = objectMapper.readTree(previewResult.getResponse().getContentAsByteArray());
        String suggestionId = preview.get("suggestion_id").asText();
        String fingerprint = preview.get("proposal_fingerprint").asText();
        mockMvc.perform(put("/trips/{tripId}/itinerary", fixture.tripId())
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"items":[{
                      "basket_item_id":%d,"day_number":1,"order_index":0,
                      "planned_arrival":"09:00","planned_duration_min":60,"is_fixed":true
                    }]}
                    """.formatted(fixture.fixedItemId())))
            .andExpect(status().isOk());

        // When / Then
        mockMvc.perform(post(
                "/trips/{tripId}/travel-guide-ai/suggestions/{suggestionId}/apply",
                fixture.tripId(), suggestionId
            )
                .with(jwt().jwt(token -> token.subject(Long.toString(fixture.ownerId()))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TravelGuideAiTestFixture.applyBody(UUID.randomUUID().toString(), fingerprint)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROPOSAL_STALE"));
        mockMvc.perform(post("/trips/{tripId}/travel-guide-ai/suggestions", fixture.tripId())
                .with(jwt().jwt(token -> token.subject(Long.toString(nonmember))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TravelGuideAiTestFixture.previewBody(fixture)))
            .andExpect(status().isForbidden());
        assertThat(jdbc.sql(
                "SELECT COUNT(*) FROM itinerary_changes WHERE trip_id = :tripId AND action = 'replace'"
            )
            .param("tripId", fixture.tripId())
            .query(Long.class)
            .single()).isOne();
    }

    private MvcResult preview(TravelGuideAiTestFixture.Fixture fixture, long userId) throws Exception {
        return mockMvc.perform(post("/trips/{tripId}/travel-guide-ai/suggestions", fixture.tripId())
                .with(jwt().jwt(token -> token.subject(Long.toString(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(TravelGuideAiTestFixture.previewBody(fixture)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private TravelGuideAiTestFixture fixtures() {
        return new TravelGuideAiTestFixture(jdbc);
    }
}
