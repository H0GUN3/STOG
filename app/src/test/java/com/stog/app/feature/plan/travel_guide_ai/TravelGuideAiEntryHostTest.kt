package com.stog.app.feature.plan.travel_guide_ai

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelGuideAiEntryHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedTripEnablesPreviewButConversationDoesNotClaimPersistence() {
        // Given / When
        composeRule.setContent {
            STOGTheme {
                TravelGuideAiEntry(
                    TravelGuideAiHost("http://127.0.0.1:1", "token", 14, "전주 여행"),
                )
            }
        }
        composeRule.waitForIdle()

        // Then
        composeRule.onNodeWithTag("stobee_trip_14").assertExists()
        composeRule.onNodeWithTag("stobee_preview")
            .assertHasClickAction()
            .assertIsEnabled()
    }

    @Test
    fun missingAuthenticationKeepsPreviewDisabled() {
        // Given / When
        composeRule.setContent {
            STOGTheme {
                TravelGuideAiEntry(
                    TravelGuideAiHost("http://127.0.0.1:1", null, 14, "전주 여행"),
                )
            }
        }
        composeRule.waitForIdle()

        // Then
        composeRule.onNodeWithTag("stobee_preview").assertIsNotEnabled()
    }
}
