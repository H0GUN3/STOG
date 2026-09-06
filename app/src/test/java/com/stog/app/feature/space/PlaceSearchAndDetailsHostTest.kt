package com.stog.app.feature.space

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaceSearchAndDetailsHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dedicatedPlaceSearchOmitsGlobalHeader() {
        composeRule.setContent {
            STOGTheme {
                PlaceSearchScreen(
                    baseUrl = "http://127.0.0.1",
                    accessToken = null,
                    onBack = {},
                    onLoginRequired = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("STOG").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("장소 검색").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("알림").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("지도 화면으로 돌아가기").assertIsDisplayed()
    }

    @Test
    fun searchResultCardShowsPreviewWhenCandidateProvidesPhoto() {
        composeRule.setContent {
            STOGTheme {
                PlaceCandidateCard(
                    candidate = PlaceSearchCandidate(
                        externalId = "place-1",
                        name = "한뚝수육국밥",
                        address = "전북특별자치도 군산시",
                        latitude = null,
                        longitude = null,
                        photoNames = listOf("photo-1"),
                    ),
                    baseUrl = "http://127.0.0.1:1",
                    saved = false,
                    saving = false,
                    onSelect = {},
                    onSave = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription("STOG 기본 장소 이미지")
            .assertIsDisplayed()
    }

    @Test
    fun mediumPlaceDetailsFillsTheLowerSheetLayoutWithPhotoRow() {
        composeRule.setContent {
            STOGTheme {
                Box(Modifier.size(width = 400.dp, height = 420.dp)) {
                    PlaceDetailsSheet(
                        state = PlaceDetailsState.Loaded(
                            details = PlaceDetails(
                                externalId = "place-1",
                                name = "아주 긴 장소 이름이 두 줄 이상으로 표시되는 장소",
                                address = "전북특별자치도 군산시 아주 긴 주소가 두 줄로 표시되는 지역",
                                latitude = null,
                                longitude = null,
                                types = listOf("restaurant"),
                                regularOpeningHours = emptyList(),
                                nationalPhoneNumber = null,
                                websiteUri = null,
                                googleMapsUri = null,
                                photoNames = emptyList(),
                            ),
                        ),
                        baseUrl = "http://127.0.0.1",
                        level = SheetLevel.HalfExpanded,
                        onClose = {},
                        onCollapse = {},
                        onSave = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription("STOG 기본 장소 이미지")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsEqualTo(220.dp)
    }
}
