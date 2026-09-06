package com.stog.app.feature.space

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaceSaveNavigationHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingPlaceRoutesNewTripCreationToTheFormalScreen() {
        var createTripRequested = false
        composeRule.setContent {
            STOGTheme {
                PlaceSearchScreen(
                    baseUrl = "http://10.0.2.2:8080",
                    accessToken = null,
                    initialPendingCandidate = PlaceSearchCandidate(
                        externalId = "place-1",
                        name = "장소",
                        address = null,
                        latitude = 35.8,
                        longitude = 127.1,
                    ),
                    onBack = {},
                    onLoginRequired = {},
                    onCreateTrip = { createTripRequested = true },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("새 여행 만들기").performClick()

        assertTrue(createTripRequested)
    }
}
