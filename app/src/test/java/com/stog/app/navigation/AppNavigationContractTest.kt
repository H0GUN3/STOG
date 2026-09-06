package com.stog.app.navigation

import com.stog.app.feature.space.MapMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationContractTest {
    @Test
    fun everyDestinationHasOneStableSavedRoute() {
        assertEquals(AppDestination.entries.size, APP_ROUTE_MAP.size)
        assertEquals(AppDestination.entries.toSet(), APP_ROUTE_MAP.values.toSet())
        assertEquals(APP_ROUTE_MAP.size, APP_ROUTE_MAP.keys.toSet().size)
    }

    @Test
    fun malformedOrUnknownSavedRouteFailsClosed() {
        assertNull(destinationFromSavedRoute(null))
        assertNull(destinationFromSavedRoute(""))
        assertNull(destinationFromSavedRoute("UNKNOWN"))
    }

    @Test
    fun homeAndStobeeTargetsUseTheirIntendedSurfaces() {
        assertEquals(AppDestination.HOME, HOME_PAGE_TARGET.destination)
        assertFalse(HOME_PAGE_TARGET.showingMap)
        assertEquals(AppDestination.HOME, STOBEE_TARGET.destination)
        assertTrue(STOBEE_TARGET.showingMap)
    }

    @Test
    fun fixedNavigationItemsHaveCompleteDestinationGeometry() {
        assertEquals(5, MAP_NAVIGATION_ITEM_COUNT)
        assertEquals(
            setOf(MapMenu.HOME, MapMenu.TRAVEL, MapMenu.SOCIAL, MapMenu.RECOMMENDATIONS),
            MAP_MENU_DESTINATIONS.keys,
        )
        assertTrue(MAP_NAVIGATION_ITEM_MIN_TOUCH_DP >= 48)
    }

    @Test
    fun destinationBackReturnsToMapBeforeLeavingTheApp() {
        assertEquals(AppDestination.HOME, AppDestination.TRAVEL.backDestination())
        assertEquals(AppDestination.TRAVEL, AppDestination.NEW_TRIP.backDestination())
        assertEquals(AppDestination.HOME, AppDestination.DISCOVER.backDestination())
        assertEquals(AppDestination.HOME, AppDestination.USER.backDestination())
        assertNull(AppDestination.HOME.backDestination())
        assertNull(AppDestination.LOGIN.backDestination())
        assertNull(AppDestination.SURVEY.backDestination())
        assertNull(AppDestination.SURVEY_RESULT.backDestination())
    }
}
