package com.stog.app.feature.home

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeCaptureActionHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cameraActionIsAccessibleAndDispatchesOneCaptureEventPerTap() {
        var captureEvents = 0
        composeRule.setContent {
            STOGTheme {
                HomeCaptureAction(onClick = { captureEvents++ })
            }
        }

        composeRule.onNodeWithContentDescription("사진 기록 촬영")
            .assertHasClickAction()
            .assertWidthIsAtLeast(StogUiContract.MinTouchTargetDp.dp)
            .assertHeightIsAtLeast(StogUiContract.MinTouchTargetDp.dp)
            .performClick()

        composeRule.runOnIdle { assertEquals(1, captureEvents) }
    }
}
