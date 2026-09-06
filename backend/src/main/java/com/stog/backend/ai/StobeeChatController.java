package com.stog.backend.ai;

import com.stog.backend.plan.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stobee")
public class StobeeChatController {
    private final StobeeChatService.StogAiClient ai;

    public StobeeChatController(StobeeChatService.StogAiClient ai) {
        this.ai = ai;
    }

    @PostMapping("/chat")
    public ChatResponse chat(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ChatRequest request
    ) {
        CurrentUserId.from(jwt);
        return ai.chat(request);
    }

    public record ChatRequest(
        @NotBlank String session_id,
        @NotBlank String message
    ) {
    }

    public record ChatResponse(
        String session_id,
        String message,
        UserUnderstanding user_understanding,
        Map<String, Object> current_trip_context,
        List<PlaceRecommendation> recommendations
    ) {
    }

    public record PlaceRecommendation(
        Long place_id,
        String provider,
        String external_id,
        String name,
        String formatted_address,
        Double latitude,
        Double longitude,
        List<String> types,
        List<String> photo_urls,
        String source_type,
        Long source_id,
        String catalog_status
    ) {
        public PlaceRecommendation {
            types = List.copyOf(types == null ? List.of() : types);
            photo_urls = List.copyOf(photo_urls == null ? List.of() : photo_urls);
        }
    }

    public record UserUnderstanding(
        List<Map<String, Object>> preference_evidence,
        List<Map<String, Object>> style_evidence,
        Map<String, Object> context_update
    ) {
        public UserUnderstanding {
            preference_evidence = List.copyOf(
                preference_evidence == null ? List.of() : preference_evidence
            );
            style_evidence = List.copyOf(
                style_evidence == null ? List.of() : style_evidence
            );
            context_update = context_update == null
                ? Map.of()
                : context_update;
        }
    }
}
