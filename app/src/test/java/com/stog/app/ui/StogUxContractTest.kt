package com.stog.app.ui

import com.stog.app.feature.space.BackAction
import com.stog.app.feature.space.BackContractState
import com.stog.app.feature.space.GestureOwner
import com.stog.app.feature.space.SheetLevel
import com.stog.app.feature.space.resolveBackAction
import com.stog.app.feature.space.resolveVerticalGestureOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StogUxContractTest {
    @Test
    fun backPrecedenceIsImeThenDragThenSheetThenDetailThenDestination() {
        val allActive = BackContractState(
            imeVisible = true,
            sheetInTransition = true,
            sheetLevel = SheetLevel.Expanded,
            detailOpen = true,
            hasDestinationParent = true,
        )
        assertEquals(BackAction.DismissIme, resolveBackAction(allActive))
        assertEquals(
            BackAction.SettleSheet,
            resolveBackAction(allActive.copy(imeVisible = false)),
        )
        assertEquals(
            BackAction.CollapseSheet,
            resolveBackAction(allActive.copy(imeVisible = false, sheetInTransition = false)),
        )
        assertEquals(
            BackAction.CloseDetail,
            resolveBackAction(
                allActive.copy(
                    imeVisible = false,
                    sheetInTransition = false,
                    sheetLevel = SheetLevel.HalfExpanded,
                ),
            ),
        )
        assertEquals(
            BackAction.NavigateToParent,
            resolveBackAction(
                allActive.copy(
                    imeVisible = false,
                    sheetInTransition = false,
                    sheetLevel = SheetLevel.Collapsed,
                    detailOpen = false,
                ),
            ),
        )
    }

    @Test
    fun dragHandleAndInnerContentNeverCompeteForOneGesture() {
        assertEquals(
            GestureOwner.Sheet,
            resolveVerticalGestureOwner(startedOnHandle = true, innerContentCanScroll = true),
        )
        assertEquals(
            GestureOwner.InnerContent,
            resolveVerticalGestureOwner(startedOnHandle = false, innerContentCanScroll = true),
        )
        assertEquals(
            GestureOwner.InnerContent,
            resolveVerticalGestureOwner(startedOnHandle = false, innerContentCanScroll = false),
        )
    }

    @Test
    fun mapOverlayGeometryUsesSharedDesignValues() {
        assertEquals(48, StogUiContract.MinTouchTargetDp)
        assertEquals(24, StogUiContract.ScreenGutterDp)
        assertEquals(70, StogUiContract.MapNavigationHeightDp)
        assertEquals(5, StogUiContract.MapNavigationItemCount)
        assertEquals(8, StogUiContract.MapOverlayGapDp)
        assertEquals(12, StogUiContract.MapSurfaceElevationDp)
        assertEquals(6, StogUiContract.MapControlElevationDp)
    }

    @Test
    fun insetOwnershipIsSingularAndImeSafe() {
        assertEquals(InsetOwner.MapTopBar, InsetEdge.MapStatusBar.owner)
        assertEquals(InsetOwner.MapNavigation, InsetEdge.MapNavigationBar.owner)
        assertEquals(InsetOwner.SearchRoot, InsetEdge.SearchStatusBar.owner)
        assertEquals(InsetOwner.SearchRoot, InsetEdge.SearchIme.owner)
        assertTrue(InsetEdge.entries.all { it.owners.size == 1 })
    }

    @Test
    fun semanticContractsHaveRolesAndNonEmptyAccessibleNames() {
        assertEquals(SemanticElement.entries.size, STOG_SEMANTICS.size)
        STOG_SEMANTICS.values.forEach { contract ->
            assertTrue(contract.accessibleName.isNotBlank())
            assertTrue(contract.role != SemanticRole.None)
        }
    }

    @Test
    fun longKoreanTitlesWrapWithoutHardCodedLineBreaks() {
        assertEquals(2, StogTextContract.PlaceTitleMaxLines)
        assertTrue(StogTextContract.PlaceTitleSoftWrap)
        assertTrue(StogTextContract.PlaceTitleUsesOverflowFallback)
        assertFalse(StogTextContract.AllowsHardCodedLineBreaks)
    }

}
