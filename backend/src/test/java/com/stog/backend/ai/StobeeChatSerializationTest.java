package com.stog.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StobeeChatSerializationTest {
    @Test
    void replacesMissingAiMessageWithUserVisibleFallback() {
        assertThat(StobeeChatService.normalizedMessage(null))
            .isEqualTo("지금은 답변을 준비하지 못했어요. 잠시 후 다시 시도해 주세요.");
        assertThat(StobeeChatService.normalizedMessage(" null "))
            .isEqualTo("지금은 답변을 준비하지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void serializesBackendOwnedPlaceRecommendations() throws Exception {
        StobeeChatController.ChatResponse response =
            new StobeeChatController.ChatResponse(
                "stobee-1",
                "추천 장소입니다.",
                null,
                Map.of(),
                List.of(
                    new StobeeChatController.PlaceRecommendation(
                        11L,
                        "canonical",
                        "tourism-11",
                        "전주 한옥마을",
                        "전북 전주시",
                        35.815,
                        127.15,
                        List.of("tourist_attraction"),
                        List.of(),
                        "tourism",
                        21L,
                        "published"
                    )
                )
            );

        JsonNode json = new ObjectMapper().readTree(
            new ObjectMapper().writeValueAsBytes(response)
        );

        assertThat(json.get("recommendations")).hasSize(1);
        assertThat(json.get("recommendations").get(0).get("place_id").asLong())
            .isEqualTo(11L);
        assertThat(json.get("recommendations").get(0).get("external_id").asText())
            .isEqualTo("tourism-11");
    }
}
