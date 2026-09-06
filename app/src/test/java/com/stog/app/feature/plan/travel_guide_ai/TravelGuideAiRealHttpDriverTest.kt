package com.stog.app.feature.plan.travel_guide_ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelGuideAiRealHttpDriverTest {
    @Test
    fun selectedTripPreviewAndIdenticalApplyReplayUseRealHttp() {
        // Given
        val baseUrl = System.getenv("STOG_TASK14_HTTP_BASE_URL")
        val token = System.getenv("STOG_TASK14_ACCESS_TOKEN")
        val tripId = System.getenv("STOG_TASK14_TRIP_ID")?.toLongOrNull()
        assumeTrue(baseUrl != null && token != null && tripId != null)
        val client = TravelGuideAiApiClient(checkNotNull(baseUrl))

        // When
        val preview = client.previewCurrentItinerary(checkNotNull(token), checkNotNull(tripId))
        val first = client.apply(
            token,
            preview,
            "00000000-0000-0000-0000-000000000014",
        )
        val replay = client.apply(
            token,
            preview,
            "00000000-0000-0000-0000-000000000014",
        )

        // Then
        assertEquals(tripId, preview.tripId)
        assertEquals(first, replay)
        assertEquals(1, preview.fixedBasketItemIds.size)
    }
}
