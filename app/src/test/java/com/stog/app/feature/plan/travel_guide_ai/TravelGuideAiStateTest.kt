package com.stog.app.feature.plan.travel_guide_ai

import com.stog.app.feature.space.BasketItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelGuideAiBasketTest {
    @Test
    fun includesEveryActiveBasketItemInProposalInput() {
        val basket = listOf(
            BasketItem(41L, "place", "카페", "cafe", "canonical", "resolved", null),
            BasketItem(42L, "place", "전시", "museum", "canonical", "manual", null),
        )

        assertEquals(listOf(41L, 42L), basketItemIdsForProposal(basket))
    }
}

class TravelGuideAiStateTest {
    @Test
    fun previewAndFailureStayBoundToSelectedTripWithoutClaimingPersistence() {
        // Given
        val selected = TravelGuideAiState.selectTrip(TravelGuideAiUiState.Idle, 14, "전주 여행")

        // When
        val loading = TravelGuideAiState.previewStarted(selected)
        val failed = TravelGuideAiState.failed(loading, authExpired = false, message = "연결 실패")

        // Then
        assertEquals(14L, failed.tripId)
        assertTrue(failed is TravelGuideAiUiState.Error)
        assertEquals(false, failed.itineraryPersisted)
    }

    @Test
    fun previewRequiresExplicitApplyBeforeAppliedState() {
        // Given
        val proposal = proposal(14)
        val selected = TravelGuideAiState.selectTrip(TravelGuideAiUiState.Idle, 14, "전주 여행")

        // When
        val preview = TravelGuideAiState.previewSucceeded(selected, proposal)
        val applying = TravelGuideAiState.applyStarted(
            preview,
            "00000000-0000-0000-0000-000000000015",
        )
        val retry = TravelGuideAiState.failed(applying, authExpired = false, message = "timeout")
        val retryApplying = TravelGuideAiState.applyStarted(
            retry,
            checkNotNull((retry as TravelGuideAiUiState.Error).clientApplyId),
        )
        val applied = TravelGuideAiState.applySucceeded(
            retryApplying,
            TravelGuideAiApplied(proposal.suggestionId, 14, 2, 31),
        )

        // Then
        assertTrue(preview is TravelGuideAiUiState.Preview)
        assertEquals(false, preview.itineraryPersisted)
        assertEquals(
            "00000000-0000-0000-0000-000000000015",
            retry.clientApplyId,
        )
        assertEquals(
            "00000000-0000-0000-0000-000000000015",
            (retryApplying as TravelGuideAiUiState.Applying).clientApplyId,
        )
        assertTrue(applied is TravelGuideAiUiState.Applied)
        assertEquals(true, applied.itineraryPersisted)
    }

    @Test
    fun conversationResponseAndStaleTripPreviewDoNotChangeState() {
        // Given
        val selected = TravelGuideAiState.selectTrip(TravelGuideAiUiState.Idle, 14, "전주 여행")

        // When
        val afterConversation = TravelGuideAiState.conversationResponse(selected, "참고 답변")
        val stalePreview = TravelGuideAiState.previewSucceeded(afterConversation, proposal(15))

        // Then
        assertEquals(selected, afterConversation)
        assertEquals(selected, stalePreview)
        assertEquals(false, stalePreview.itineraryPersisted)
    }

    private fun proposal(tripId: Long) = TravelGuideAiProposal(
        suggestionId = "00000000-0000-0000-0000-000000000014",
        tripId = tripId,
        baseVersion = 1,
        status = "ready",
        fingerprint = "a".repeat(64),
        feasible = true,
        actions = listOf(
            TravelGuideAiAction(0, 41, 1, 0, "09:00", 60, 0, true),
        ),
        fixedBasketItemIds = listOf(41),
        excludedUnresolvedBasketItemIds = emptyList(),
        violations = emptyList(),
    )
}
