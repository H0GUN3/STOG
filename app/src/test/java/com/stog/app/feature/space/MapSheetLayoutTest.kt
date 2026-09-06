package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapSheetLayoutTest {
    @Test
    fun usesMeasuredSearchAndContentBoundsForAllThreeAnchors() {
        val anchors = MapSheetMeasurements(
            topChromeBottomPx = 100f,
            contentBottomPx = 720f,
            expandedMarginPx = 20f,
            collapsedHeightPx = 96f,
        ).toAnchors()

        assertEquals(120f, anchors.expandedTopPx, 0.001f)
        assertEquals(392.16f, anchors.halfExpandedTopPx, 0.001f)
        assertEquals(624f, anchors.collapsedTopPx, 0.001f)
    }

    @Test
    fun defaultAnchorUsesConfiguredVisibleRatio() {
        val anchors = MapSheetMeasurements(
            topChromeBottomPx = 100f,
            contentBottomPx = 720f,
            expandedMarginPx = 20f,
            collapsedHeightPx = 96f,
        ).toAnchors()

        assertEquals(
            HALF_EXPANDED_VISIBLE_RATIO,
            (anchors.collapsedTopPx - anchors.halfExpandedTopPx) / anchors.availableRangePx,
            0.001f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingSearchAndSheetAnchors() {
        MapSheetMeasurements(
            topChromeBottomPx = 700f,
            contentBottomPx = 720f,
            expandedMarginPx = 20f,
            collapsedHeightPx = 96f,
        ).toAnchors()
    }

    @Test
    fun backStepsExpandedToDefaultThenHidden() {
        assertEquals(SheetLevel.HalfExpanded, SheetLevel.Expanded.backTarget())
        assertEquals(SheetLevel.Collapsed, SheetLevel.HalfExpanded.backTarget())
        assertNull(SheetLevel.Collapsed.backTarget())
    }

    @Test
    fun sheetSurfaceHeightEndsAtViewportAfterApplyingOffset() {
        assertEquals(440f, visibleSheetHeightPx(720, 280f), 0.001f)
        assertEquals(0f, visibleSheetHeightPx(720, 900f), 0.001f)
    }
}
