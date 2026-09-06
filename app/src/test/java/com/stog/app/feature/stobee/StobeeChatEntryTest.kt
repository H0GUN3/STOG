package com.stog.app.feature.stobee

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StobeeChatEntryTest {
    @Test
    fun parsesQwenMessageAndKeepsSessionId() {
        val response = parseStobeeChatResponse(
            responseBody = """
                {
                  "session_id": "stobee-123",
                  "message": "전주 여행을 도와드릴게요.",
                  "user_understanding": {}
                }
            """.trimIndent(),
            fallbackSessionId = "fallback",
        )

        assertEquals("stobee-123", response.sessionId)
        assertEquals("전주 여행을 도와드릴게요.", response.message)
    }

    @Test
    fun fallsBackToRequestSessionWhenQwenOmitsSessionId() {
        val response = parseStobeeChatResponse(
            responseBody = """{"message":"좋아요."}""",
            fallbackSessionId = "stobee-fallback",
        )

        assertEquals("stobee-fallback", response.sessionId)
    }

    @Test
    fun rejectsNullMessageInsteadOfRenderingLiteralNull() {
        assertThrows(IOException::class.java) {
            parseStobeeChatResponse(
                responseBody = """{"message":null}""",
                fallbackSessionId = "fallback",
            )
        }
    }

    @Test
    fun parsesExactlyFiveBackendOwnedPlaceRecommendations() {
        val response = parseStobeeChatResponse(
            responseBody = """
                {
                  "session_id": "stobee-123",
                  "message": "추천 장소를 골라보세요.",
                  "recommendations": [
                    {"place_id": 1, "provider": "canonical", "external_id": "p1", "name": "장소 1"},
                    {"place_id": 2, "provider": "canonical", "external_id": "p2", "name": "장소 2"},
                    {"place_id": 3, "provider": "canonical", "external_id": "p3", "name": "장소 3"},
                    {"place_id": 4, "provider": "canonical", "external_id": "p4", "name": "장소 4"},
                    {"place_id": 5, "provider": "canonical", "external_id": "p5", "name": "장소 5"}
                  ]
                }
            """.trimIndent(),
            fallbackSessionId = "fallback",
        )

        assertEquals(5, response.recommendations.size)
        assertEquals(5L, response.recommendations.last().placeId)
    }

    @Test
    fun recognizesNaturalRecommendationRequest() {
        assertTrue(isItineraryRequest("카페와 전시 위주로 일정 짜줘"))
    }

    @Test
    fun scrollsPastRecommendationsAndSendingIndicator() {
        assertEquals(
            3,
            stobeeChatLastItemIndex(
                messageCount = 2,
                hasRecommendations = true,
                sending = true,
            ),
        )
    }
}
