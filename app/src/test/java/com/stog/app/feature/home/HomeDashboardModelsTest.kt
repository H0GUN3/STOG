package com.stog.app.feature.home

import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.humanizedPlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardModelsTest {
    @Test
    fun homeShowsOnlyActiveAndUpcomingTrips() {
        assertTrue(isHomeTripVisible(trip("active")))
        assertTrue(isHomeTripVisible(trip("dormant")))
        assertFalse(isHomeTripVisible(trip("ended")))
    }

    @Test
    fun homeUsesSpecificLabelsForNonTourismPlaceTypes() {
        assertEquals("카페", humanizedPlaceCategory(listOf("cafe")))
        assertEquals("음식점", humanizedPlaceCategory(listOf("restaurant")))
        assertEquals("숙소", humanizedPlaceCategory(listOf("lodging")))
        assertEquals("쇼핑", humanizedPlaceCategory(listOf("shopping_mall")))
    }

    private fun trip(mode: String) = TripSummary(
        id = 1L,
        title = "여행",
        activityType = "tour",
        mode = mode,
        visibility = "private",
    )
}
