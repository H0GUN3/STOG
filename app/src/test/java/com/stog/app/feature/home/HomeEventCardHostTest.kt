package com.stog.app.feature.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stog.app.feature.space.EventSearchCandidate
import com.stog.app.feature.space.EventSearchProvenance
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeEventCardHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun eventCardShowsEventLabelDateAndVenue() {
        composeRule.setContent {
            STOGTheme {
                HomeEventCard(
                    event = EventSearchCandidate(
                        externalId = "1001",
                        title = "전주 비빔밥 축제",
                        venueName = "전주월드컵경기장",
                        address = "전북 전주시",
                        latitude = 35.846,
                        longitude = 127.126,
                        startsOn = "2026-10-10",
                        endsOn = "2026-10-12",
                        detailUri = null,
                        imageUri = null,
                        provenance = EventSearchProvenance.TourApi,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("행사").assertIsDisplayed()
        composeRule.onNodeWithText("전주 비빔밥 축제").assertIsDisplayed()
        composeRule.onNodeWithText("10/10 - 10/12").assertIsDisplayed()
        composeRule.onNodeWithText("전주월드컵경기장").assertIsDisplayed()
    }

    @Test
    fun recommendationCardShowsSpecificPlaceCategory() {
        composeRule.setContent {
            STOGTheme {
                HomeRecommendationCard(
                    candidate = PlaceSearchCandidate(
                        externalId = "restaurant-1",
                        name = "군산 맛집",
                        address = "전북 군산시",
                        latitude = 35.967,
                        longitude = 126.736,
                        types = listOf("restaurant"),
                    ),
                    baseUrl = "",
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("음식점").assertIsDisplayed()
    }
}
