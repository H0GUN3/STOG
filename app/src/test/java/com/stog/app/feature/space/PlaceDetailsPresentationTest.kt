package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceDetailsPresentationTest {
    @Test
    fun formatsOnlyAvailableRatingParts() {
        assertEquals("4.6 · 리뷰 128", placeRatingSummary(4.6, 128))
        assertEquals("4.6", placeRatingSummary(4.6, null))
        assertNull(placeRatingSummary(null, 128))
    }

    @Test
    fun summarizesCurrentOpeningStateWithoutWeekdayDump() {
        assertEquals(
            "영업 중 · 23:00까지",
            placeOpeningSummary(
                businessStatus = "OPERATIONAL",
                openNow = true,
                nextCloseTime = "2026-08-24T23:00:00+09:00",
            ),
        )
        assertEquals(
            "영업 종료",
            placeOpeningSummary("OPERATIONAL", false, null),
        )
        assertEquals(
            "임시 휴업",
            placeOpeningSummary("CLOSED_TEMPORARILY", null, null),
        )
    }

    @Test
    fun formatsDistanceWithoutInventingMissingLocation() {
        assertEquals("750m", formatPlaceDistance(750f))
        assertEquals("1.5km", formatPlaceDistance(1_500f))
        assertNull(formatPlaceDistance(null))
    }
}
