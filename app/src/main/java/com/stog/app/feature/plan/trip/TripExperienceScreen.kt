package com.stog.app.feature.plan.trip

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.stog.app.feature.space.MapMenu
import com.stog.app.feature.space.ItineraryDetailScreen
import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.TripManagementPanel
import com.stog.app.feature.space.StogMainScaffold
import com.stog.app.feature.plan.share_import.StoredShareImport

@Composable
internal fun TripExperienceScreen(
    baseUrl: String,
    accessToken: String?,
    confirmedShareImports: List<StoredShareImport>,
    onLoginRequired: () -> Unit,
    onCapture: () -> Unit,
    onArchive: () -> Unit,
    onSearch: () -> Unit,
    onMenuSelected: (MapMenu) -> Unit,
    onOpenStobee: () -> Unit,
    onCreateTrip: () -> Unit = {},
    tripListRefreshKey: Int = 0,
    initialTrip: TripSummary? = null,
    onInitialTripCleared: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var itineraryTrip by remember(initialTrip?.id) { mutableStateOf(initialTrip) }
    val selectedTrip = itineraryTrip
    if (selectedTrip != null) {
        ItineraryDetailScreen(
            baseUrl = baseUrl,
            accessToken = accessToken,
            trip = selectedTrip,
            onBack = {
                itineraryTrip = null
                onInitialTripCleared()
            },
            modifier = modifier.fillMaxSize(),
        )
    } else {
        StogMainScaffold(
            selectedMenu = MapMenu.TRAVEL,
            onSearch = onSearch,
            onMenuSelected = onMenuSelected,
            onOpenStobee = onOpenStobee,
            showHeader = false,
            modifier = modifier.fillMaxSize(),
        ) {
            TripManagementPanel(
                baseUrl = baseUrl,
                accessToken = accessToken,
                confirmedShareImports = confirmedShareImports,
                onLoginRequired = onLoginRequired,
                onCapture = onCapture,
                onArchive = onArchive,
                onOpenItinerary = { itineraryTrip = it },
                onCreateTrip = onCreateTrip,
                tripListRefreshKey = tripListRefreshKey,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
