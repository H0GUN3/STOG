package com.stog.app.feature.stobee

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StobeeChatEntryHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectsExistingTripBeforeRequestingItineraryProposal() {
        var selectedTripId = 0L
        var selectedTripTitle = ""

        composeRule.setContent {
            STOGTheme {
                StobeeChatEntry(
                    baseUrl = "http://127.0.0.1",
                    accessToken = "token",
                    level = com.stog.app.feature.space.SheetLevel.HalfExpanded,
                    tripId = null,
                    onActivate = {},
                    onLoginRequired = {},
                    onSelectTrip = { id, title ->
                        selectedTripId = id
                        selectedTripTitle = title
                    },
                    tripLoader = {
                        listOf(
                            TripSummary(
                                id = 14L,
                                title = "전주 여행",
                                activityType = "tour",
                                mode = "planning",
                                visibility = "private",
                            ),
                        )
                    },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stobee_input_bar").assertExists()
        composeRule.onNodeWithTag("stobee_trip_option_14").assertExists().performClick()

        assertEquals(14L, selectedTripId)
        assertEquals("전주 여행", selectedTripTitle)
    }
}
