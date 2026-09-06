package com.stog.app.navigation

import com.stog.app.feature.space.MapMenu
import com.stog.app.ui.StogUiContract

internal enum class AppDestination(val savedRoute: String) {
    LOGIN("login"),
    HOME("home"),
    PLACE_SEARCH("place_search"),
    CAPTURE("capture"),
    ARCHIVE("archive"),
    TRAVEL("travel"),
    NEW_TRIP("new_trip"),
    DISCOVER("discover"),
    USER("user"),
    SURVEY("survey"),
    SURVEY_RESULT("survey_result"),
}

internal data class AppNavigationTarget(
    val destination: AppDestination,
    val showingMap: Boolean,
)

internal val HOME_PAGE_TARGET = AppNavigationTarget(
    destination = AppDestination.HOME,
    showingMap = false,
)

internal val STOBEE_TARGET = AppNavigationTarget(
    destination = AppDestination.HOME,
    showingMap = true,
)

internal val APP_ROUTE_MAP: Map<String, AppDestination> =
    AppDestination.entries.associateBy(AppDestination::savedRoute)

internal fun destinationFromSavedRoute(savedRoute: String?): AppDestination? =
    savedRoute?.takeIf(String::isNotBlank)?.let(APP_ROUTE_MAP::get)

internal val MAP_MENU_DESTINATIONS: Map<MapMenu, AppDestination> = mapOf(
    MapMenu.HOME to AppDestination.HOME,
    MapMenu.TRAVEL to AppDestination.TRAVEL,
    MapMenu.SOCIAL to AppDestination.DISCOVER,
    MapMenu.RECOMMENDATIONS to AppDestination.USER,
)

internal const val MAP_NAVIGATION_ITEM_COUNT = StogUiContract.MapNavigationItemCount
internal const val MAP_NAVIGATION_ITEM_MIN_TOUCH_DP = StogUiContract.MinTouchTargetDp

internal fun AppDestination.backDestination(): AppDestination? = when (this) {
    AppDestination.NEW_TRIP -> AppDestination.TRAVEL
    AppDestination.TRAVEL,
    AppDestination.DISCOVER,
    AppDestination.USER,
    AppDestination.PLACE_SEARCH,
    AppDestination.CAPTURE,
    AppDestination.ARCHIVE,
    -> AppDestination.HOME
    AppDestination.LOGIN,
    AppDestination.HOME,
    AppDestination.SURVEY,
    AppDestination.SURVEY_RESULT,
    -> null
}
