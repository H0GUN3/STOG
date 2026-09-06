package com.stog.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StogStatePanelHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun productionPanelExposesMachineStateAndExactlyOneAvailableRecoveryAction() {
        composeRule.setContent {
            STOGTheme {
                Column {
                    StogSurfaceState.entries.forEach { state ->
                        val action = defaultActionFor(state)
                        StogStatePanel(
                            state = state,
                            title = "state title",
                            detail = "state detail",
                            action = action,
                            actionLabel = if (action == StogSurfaceAction.NONE) null else "action",
                            onAction = {},
                        )
                    }
                }
            }
        }

        StogSurfaceState.entries.forEach { state ->
            val config = composeRule.onNodeWithTag(stogStatePanelTag(state))
                .fetchSemanticsNode().config
            assertEquals(state.machineName, config.getOrNull(SemanticsProperties.StateDescription))
        }
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(
            StogSurfaceState.entries.count { defaultActionFor(it) != StogSurfaceAction.NONE },
        )
    }
}
