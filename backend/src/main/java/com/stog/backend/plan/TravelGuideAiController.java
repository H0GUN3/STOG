package com.stog.backend.plan;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/trips/{tripId}")
public class TravelGuideAiController {
    private final TravelGuideAiPreviewService previews;
    private final TravelGuideAiApplyService applies;

    public TravelGuideAiController(
        TravelGuideAiPreviewService previews,
        TravelGuideAiApplyService applies
    ) {
        this.previews = previews;
        this.applies = applies;
    }

    @PostMapping({"/travel-guide-ai/suggestions", "/itinerary/proposals/preview"})
    public TravelGuideAiResponses.Preview preview(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @Valid @RequestBody TravelGuideAiRequests.Preview request
    ) {
        return previews.preview(CurrentUserId.from(jwt), tripId, request);
    }

    @PostMapping({
        "/travel-guide-ai/suggestions/{suggestionId}/apply",
        "/itinerary/proposals/{suggestionId}/apply"
    })
    public TravelGuideAiResponses.Applied apply(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long tripId,
        @PathVariable UUID suggestionId,
        @Valid @RequestBody TravelGuideAiRequests.Apply request
    ) {
        return applies.apply(
            CurrentUserId.from(jwt),
            new TravelGuideAiApplyService.ProposalScope(tripId, suggestionId),
            request
        );
    }
}
