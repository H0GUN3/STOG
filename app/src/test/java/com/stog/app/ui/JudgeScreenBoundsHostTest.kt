package com.stog.app.ui

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.stog.app.feature.auth.LoginScreen
import com.stog.app.feature.plan.trip.TripExperienceScreen
import com.stog.app.feature.profile.UserProfileScreen
import com.stog.app.feature.record.PhotoCaptureScreen
import com.stog.app.feature.social.SocialFeedScreen
import com.stog.app.feature.social.SocialFeedUiState
import com.stog.app.feature.space.PlaceSearchScreen
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JudgeScreenBoundsHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun productionLoginControlsMeetMinimumTouchTarget() {
        composeRule.setContent {
            STOGTheme {
                LoginScreen(null, false, {}, {}, {})
            }
        }
        assertEveryActionIsLargeEnough()
    }

    @Test
    fun productionSearchControlsMeetMinimumTouchTarget() {
        composeRule.setContent {
            STOGTheme {
                PlaceSearchScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    onBack = {},
                    onLoginRequired = { _ -> },
                )
            }
        }
        assertEveryActionIsLargeEnough()
    }

    @Test
    fun productionMyTripsAuthSurfaceControlsMeetMinimumTouchTarget() {
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
                )
            }
        }
        assertEveryActionIsLargeEnough()
    }

    @Test
    fun productionDiscoverControlsMeetMinimumTouchTarget() {
        composeRule.setContent {
            STOGTheme {
                SocialFeedScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    onLoginRequired = {},
                    initialState = SocialFeedUiState.Empty,
                    autoRefresh = false,
                )
            }
        }
        assertEveryActionIsLargeEnough()
    }

    @Test
    fun productionProfileControlsMeetMinimumTouchTarget() {
        composeRule.setContent {
            STOGTheme {
                UserProfileScreen(null, false, {}, {}, {}, {})
            }
        }
        assertEveryActionIsLargeEnough()
    }

    @Test
    fun productionCaptureControlsMeetMinimumTouchTarget() {
        composeRule.setContent {
            STOGTheme {
                PhotoCaptureScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    userId = null,
                    onBack = {},
                    onAuthenticationRequired = {},
                )
            }
        }
        assertEveryActionIsLargeEnough()
    }

    private fun assertEveryActionIsLargeEnough() {
        val actions = composeRule.onAllNodes(hasClickAction())
        val count = actions.fetchSemanticsNodes().size
        check(count > 0)
        repeat(count) { index ->
            try {
                actions[index]
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
            } catch (failure: AssertionError) {
                val config = actions[index].fetchSemanticsNode().config
                throw AssertionError("action[$index] $config", failure)
            }
        }
    }
}
