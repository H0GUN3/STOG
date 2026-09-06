package com.stog.app.ui

import com.stog.app.feature.space.SheetLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class MapSheetAnchorTest {
    @Test
    fun exposesOnlyTheApprovedSheetLevels() {
        assertEquals(
            listOf(
                SheetLevel.Collapsed,
                SheetLevel.HalfExpanded,
                SheetLevel.Expanded,
            ),
            SheetLevel.entries.toList(),
        )
    }

    @Test
    fun dragHandleUsesMinimumInteractiveTouchHeight() {
        assertEquals(48, BOTTOM_SHEET_HANDLE_TOUCH_HEIGHT_DP)
    }
}
