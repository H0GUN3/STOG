package com.stog.app.feature.home

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.plan.trip.TripExperienceScreen
import com.stog.app.feature.plan.trip.TripCardData
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeTripActionsHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun myTripsHeaderExposesOnlyMoreAction() {
        var moreClicked = false

        composeRule.setContent {
            STOGTheme {
                HomeSectionTitle(
                    title = "내 여행",
                    onMore = { moreClicked = true },
                )
            }
        }

        composeRule.onAllNodesWithText("여행 만들기").assertCountEquals(0)
        composeRule.onNodeWithText("더보기").assertIsDisplayed().performClick()

        assertTrue(moreClicked)
    }

    @Test
    fun emptyTripCreateCardMatchesTripCardSizeAndNavigates() {
        var createClicked = 0

        composeRule.setContent {
            STOGTheme {
                Column(Modifier.width(320.dp)) {
                    HomeTripCard(
                        card = TripCardData(
                            trip = TripSummary(
                                id = 1L,
                                title = "서울 여행",
                                activityType = "tour",
                                mode = "dormant",
                                visibility = "private",
                            ),
                            imageRes = R.drawable.stog_travel_hanok,
                        ),
                        onClick = {},
                        modifier = Modifier.testTag("home_reference_trip_card"),
                    )
                    HomeCreateTripCard(
                        onClick = { createClicked++ },
                        modifier = Modifier.testTag("home_create_trip_card"),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("home_reference_trip_card")
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(140.dp)
        composeRule.onNodeWithTag("home_create_trip_card")
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(140.dp)
        composeRule.onNodeWithContentDescription("새 여행 만들기")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertTrue(createClicked == 1)
    }

    @Test
    fun homeTripSummaryExposesFullDetailAction() {
        var detailClicked = false

        composeRule.setContent {
            STOGTheme {
                HomeTripItineraryContent(
                    trip = TripSummary(
                        id = 1L,
                        title = "서울 여행",
                        activityType = "tour",
                        mode = "dormant",
                        visibility = "private",
                    ),
                    itinerary = emptyList(),
                    loading = false,
                    onOpenDetail = { detailClicked = true },
                )
            }
        }

        composeRule.onNodeWithText("상세 더보기").assertIsDisplayed().performClick()

        assertTrue(detailClicked)
    }

    @Test
    fun travelRouteCanStartOnTheSelectedTripDetail() {
        composeRule.setContent {
            STOGTheme {
                TripExperienceScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    confirmedShareImports = emptyList(),
                    onLoginRequired = {},
                    onCapture = {},
                    onArchive = {},
                    onSearch = {},
                    onMenuSelected = {},
                    onOpenStobee = {},
                    initialTrip = TripSummary(
                        id = 1L,
                        title = "서울 여행",
                        activityType = "tour",
                        mode = "dormant",
                        visibility = "private",
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("여행 관리 메뉴").assertIsDisplayed()
    }
}
