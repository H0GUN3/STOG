package com.stog.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.stog.app.feature.space.SheetLevel
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StogMapBottomSheetHostSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun productionHandleTransitionsBothDirectionsAndRejectsRaces() {
        val state = composeProductionSheet(SheetLevel.Collapsed)
        composeRule.mainClock.autoAdvance = false

        assertHandleLevel(SheetLevel.Collapsed)
        assertNull(handleNode().fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollBy))
        assertTrue(
            composeRule.onNodeWithTag(SHEET_CONTENT_TEST_TAG)
                .fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollBy) != null,
        )

        val collapsedAdvance = handleCustomActions().single().action
        assertTrue(collapsedAdvance())
        assertFalse(collapsedAdvance())
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertAdjustmentActionsUnavailable()
        settle(state, SheetLevel.HalfExpanded)
        assertHandleLevel(SheetLevel.HalfExpanded)

        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle {
            state.updateHandleDragging(true)
            assertFalse(state.canStartAccessibilityAdjustment)
        }
        composeRule.waitForIdle()
        assertAdjustmentActionsUnavailable()
        composeRule.runOnIdle { state.updateHandleDragging(false) }
        composeRule.waitForIdle()
        assertEquals(2, handleCustomActions().size)
        composeRule.runOnIdle {
            state.beginAnimation()
            assertTrue(state.isInTransition)
            assertFalse(state.canStartAccessibilityAdjustment)
        }
        composeRule.waitForIdle()
        assertAdjustmentActionsUnavailable()
        composeRule.runOnIdle { state.endAnimation() }
        composeRule.waitForIdle()
        assertEquals(2, handleCustomActions().size)
        composeRule.mainClock.autoAdvance = false

        val expand = handleSetProgress()
        assertTrue(expand(SheetLevel.Expanded.ordinal.toFloat()))
        assertFalse(expand(SheetLevel.Expanded.ordinal.toFloat()))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertAdjustmentActionsUnavailable()
        settle(state, SheetLevel.Expanded)
        assertHandleLevel(SheetLevel.Expanded)
        assertEquals(1, handleCustomActions().size)

        assertTrue(handleCustomActions().single().action())
        settle(state, SheetLevel.HalfExpanded)
        assertHandleLevel(SheetLevel.HalfExpanded)

        assertTrue(handleSetProgress()(SheetLevel.Collapsed.ordinal.toFloat()))
        settle(state, SheetLevel.Collapsed)
        assertHandleLevel(SheetLevel.Collapsed)
        assertEquals(1, handleCustomActions().size)
    }

    @Test
    fun productionHandleRejectsNonFiniteAndBoundsFiniteProgress() {
        val state = composeProductionSheet(SheetLevel.Collapsed)
        composeRule.mainClock.autoAdvance = false
        val setProgress = handleSetProgress()

        assertFalse(setProgress(Float.NaN))
        assertFalse(setProgress(Float.POSITIVE_INFINITY))
        assertFalse(setProgress(Float.NEGATIVE_INFINITY))
        assertTrue(setProgress(-100f))
        assertFalse(setProgress(100f))
        composeRule.runOnIdle { assertEquals(SheetLevel.Collapsed, state.settledLevel) }
        assertHandleLevel(SheetLevel.Collapsed)
    }

    @Test
    fun productionHandleDoesNotExistBeforeMeasuredAnchors() {
        composeRule.setContent {
            STOGTheme {
                val state = rememberStogMapBottomSheetState(SheetLevel.Collapsed)
                StogMapBottomSheet(
                    state = state,
                    onSearch = {},
                    mapContent = {},
                    sheetContent = {},
                    modifier = Modifier
                        .width(SHEET_WIDTH_DP.dp)
                        .height(0.dp),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("지도 정보 시트 높이 조절").assertCountEquals(0)
    }

    @Test
    fun placeDetailsKeepsTheTopBarForUnifiedSheetAnchors() {
        composeRule.setContent {
            STOGTheme {
                val state = rememberStogMapBottomSheetState(SheetLevel.HalfExpanded)
                StogMapBottomSheet(
                    state = state,
                    onSearch = {},
                    mapContent = {},
                    sheetContent = {},
                    modifier = Modifier
                        .width(SHEET_WIDTH_DP.dp)
                        .height(SHEET_HEIGHT_DP.dp),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("장소 검색").assertCountEquals(1)
    }

    @Test
    fun menuStateCanHideTheBottomSheetWhileKeepingTheMapHeader() {
        composeRule.setContent {
            STOGTheme {
                val state = rememberStogMapBottomSheetState(SheetLevel.Collapsed)
                StogMapBottomSheet(
                    state = state,
                    onSearch = {},
                    mapContent = {},
                    sheetContent = {},
                    showBottomSheet = false,
                    modifier = Modifier
                        .width(SHEET_WIDTH_DP.dp)
                        .height(SHEET_HEIGHT_DP.dp),
                )
            }
        }

        composeRule.onNodeWithTag(STOG_MAP_SHEET_HANDLE_TEST_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription("장소 검색").assertCountEquals(1)
    }

    @Test
    fun searchResultUsesTheSearchHeaderInsteadOfTheLegacyActionHeader() {
        composeRule.setContent {
            STOGTheme {
                val state = rememberStogMapBottomSheetState(SheetLevel.HalfExpanded)
                StogMapBottomSheet(
                    state = state,
                    onSearch = {},
                    onSearchResultBack = {},
                    mapContent = {},
                    sheetContent = {},
                    searchResultHeader = true,
                    modifier = Modifier
                        .width(SHEET_WIDTH_DP.dp)
                        .height(SHEET_HEIGHT_DP.dp),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("장소 검색").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("지도 화면으로 돌아가기").assertCountEquals(1)
    }

    private fun composeProductionSheet(
        initialLevel: SheetLevel,
    ): StogMapBottomSheetState {
        lateinit var state: StogMapBottomSheetState
        composeRule.setContent {
            STOGTheme {
                state = rememberStogMapBottomSheetState(initialLevel)
                StogMapBottomSheet(
                    state = state,
                    onSearch = {},
                    mapContent = {},
                    sheetContent = { ScrollOwnedSheetContent() },
                    modifier = Modifier
                        .width(SHEET_WIDTH_DP.dp)
                        .height(SHEET_HEIGHT_DP.dp),
                )
            }
        }
        composeRule.waitForIdle()
        handleNode().fetchSemanticsNode()
        return state
    }

    @Composable
    private fun ScrollOwnedSheetContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SHEET_CONTENT_TEST_TAG)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height((SHEET_HEIGHT_DP * 2).dp))
        }
    }

    private fun handleNode(): SemanticsNodeInteraction =
        composeRule.onNodeWithTag(STOG_MAP_SHEET_HANDLE_TEST_TAG)

    private fun handleCustomActions() = checkNotNull(
        handleNode().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions),
    )

    private fun handleSetProgress(): (Float) -> Boolean = checkNotNull(
        handleNode().fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action,
    )

    private fun assertAdjustmentActionsUnavailable() {
        val config = handleNode().fetchSemanticsNode().config
        assertTrue(config.getOrNull(SemanticsActions.CustomActions).orEmpty().isEmpty())
        assertNull(config.getOrNull(SemanticsActions.SetProgress))
    }

    private fun assertHandleLevel(expected: SheetLevel) {
        val config = handleNode().fetchSemanticsNode().config
        val range = checkNotNull(config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertEquals(expected.ordinal.toFloat(), range.current)
        assertTrue(checkNotNull(config.getOrNull(SemanticsProperties.StateDescription)).isNotBlank())
    }

    private fun settle(
        state: StogMapBottomSheetState,
        expected: SheetLevel,
    ) {
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 5_000) {
            state.settledLevel == expected && !state.isInTransition
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private companion object {
        const val SHEET_WIDTH_DP = 400
        const val SHEET_HEIGHT_DP = 700
        const val SHEET_CONTENT_TEST_TAG = "stog_map_sheet_content"
    }
}
