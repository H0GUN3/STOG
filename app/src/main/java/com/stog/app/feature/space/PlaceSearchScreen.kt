package com.stog.app.feature.space

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PLACE_SEARCH_HISTORY_PREFERENCES = "place_search_history"
private const val PLACE_SEARCH_HISTORY_KEY = "queries"

private fun loadRecentQueries(preferences: SharedPreferences): List<String> =
    preferences.getString(PLACE_SEARCH_HISTORY_KEY, "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .take(4)
        .toList()

private fun saveRecentQueries(
    preferences: SharedPreferences,
    queries: List<String>,
) {
    preferences.edit()
        .putString(PLACE_SEARCH_HISTORY_KEY, queries.joinToString("\n"))
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceSearchScreen(
    baseUrl: String,
    accessToken: String?,
    initialCandidates: List<PlaceSearchCandidate> = emptyList(),
    initialPendingCandidate: PlaceSearchCandidate? = null,
    onBack: () -> Unit,
    onLoginRequired: (PlaceSearchCandidate) -> Unit,
    onBookmarkHandled: (TripSummary) -> Unit = {},
    onSearchResults: (List<PlaceSearchCandidate>) -> Unit = {},
    onSelectCandidate: (PlaceSearchCandidate) -> Unit = {},
    onCreateTrip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val client = remember(baseUrl) { PlaceApiClient(baseUrl) }
    val planningClient = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recentSearchPreferences = remember(context) {
        context.getSharedPreferences(
            PLACE_SEARCH_HISTORY_PREFERENCES,
            Context.MODE_PRIVATE,
        )
    }
    val queryFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var query by remember { mutableStateOf("") }
    var searchState by remember(initialCandidates, recentSearchPreferences) {
        mutableStateOf(
            PlaceSearchState(
                candidates = initialCandidates,
                recentQueries = loadRecentQueries(recentSearchPreferences),
            ),
        )
    }
    var availableTrips by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
    var loadingTrips by remember { mutableStateOf(false) }
    var tripLoadMessage by remember { mutableStateOf<String?>(null) }
    var tripSelectionSheetOpen by remember { mutableStateOf(false) }
    val tripSelectionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    LaunchedEffect(Unit) {
        queryFocusRequester.requestFocus()
        withFrameNanos { }
        keyboardController?.show()
    }

    LaunchedEffect(initialPendingCandidate?.externalId, initialPendingCandidate?.provenance) {
        initialPendingCandidate?.takeIf { searchState.pendingCandidate == null }?.let { candidate ->
            searchState = searchState.resumePendingCandidate(candidate)
        }
    }

    LaunchedEffect(searchState.pendingCandidate?.externalId) {
        tripSelectionSheetOpen = searchState.pendingCandidate != null
    }

    LaunchedEffect(searchState.pendingCandidate, accessToken) {
        if (searchState.pendingCandidate == null || accessToken.isNullOrBlank()) return@LaunchedEffect
        loadingTrips = true
        tripLoadMessage = null
        runCatching {
            withContext(Dispatchers.IO) { planningClient.listTrips(accessToken) }
        }.onSuccess { availableTrips = it }
            .onFailure {
                availableTrips = emptyList()
                tripLoadMessage = "여행 목록을 불러오지 못했어요."
            }
        loadingTrips = false
    }

    fun saveCandidate(candidate: PlaceSearchCandidate, trip: TripSummary) {
        val start = searchState.beginSave(candidate, trip.id)
        searchState = start.state
        val request = start.request ?: return
        scope.launch {
            val token = accessToken ?: return@launch
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    planningClient.addToBasket(token, request.tripId, request.candidate)
                }
            }
            val requestIsCurrent = searchState.isCurrent(request)
            searchState = searchState.completeSave(request, result)
            if (requestIsCurrent && result.isSuccess) {
                tripSelectionSheetOpen = false
                onBookmarkHandled(trip)
            }
            result.exceptionOrNull()
                ?.takeIf { requestIsCurrent && it.isPlaceSaveAuthenticationFailure() }
                ?.let { onLoginRequired(request.candidate) }
        }
    }

    fun searchPlaces(term: String) {
        val start = searchState.beginSearch(term)
        searchState = start.state
        val request = start.request ?: return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    client.search(request.term)
                }
            }
            val requestIsCurrent = searchState.isCurrent(request)
            searchState = searchState.completeSearch(request, result)
            if (requestIsCurrent && result.isSuccess) {
                saveRecentQueries(recentSearchPreferences, searchState.recentQueries)
                onSearchResults(result.getOrThrow())
            }
        }
    }

    val backAction = resolveBackAction(
        BackContractState(
            imeVisible = WindowInsets.ime.getBottom(density) > 0,
            sheetInTransition = false,
            sheetLevel = SheetLevel.Collapsed,
            detailOpen = false,
            hasDestinationParent = true,
        ),
    )
    BackHandler {
        when (backAction) {
            BackAction.DismissIme -> keyboardController?.hide()
            BackAction.NavigateToParent -> onBack()
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StogSurface)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            PlaceSearchField(
                query = query,
                searching = searchState.searching,
                focusRequester = queryFocusRequester,
                onBack = onBack,
                onQueryChange = {
                    query = it
                    searchState = searchState.invalidateForQueryChange()
                },
                onSearch = { searchPlaces(query) },
            )
        }
        PlaceSearchCategoryRow(
            onCategorySelected = { category ->
                query = category
                searchState = searchState.invalidateForQueryChange()
                searchPlaces(category)
            },
        )
        HorizontalDivider(color = StogBorder.copy(alpha = 0.65f))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(StogSurface),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (searchState.searching) {
                item {
                    StogStatePanel(
                        state = StogSurfaceState.LOADING,
                        title = "장소를 찾는 중",
                        detail = "STOG 카탈로그와 장소 검색 결과를 확인하고 있어요.",
                    )
                }
            } else if (searchState.failure != null) {
                item {
                    val currentFailure = checkNotNull(searchState.failure)
                    val surfaceState = when (currentFailure) {
                        PlaceSearchFailure.OFFLINE -> StogSurfaceState.OFFLINE
                        PlaceSearchFailure.AUTH_EXPIRED -> StogSurfaceState.AUTH_EXPIRED
                        PlaceSearchFailure.UNKNOWN -> StogSurfaceState.ERROR
                    }
                    StogStatePanel(
                        state = surfaceState,
                        title = searchState.message ?: "장소를 확인하지 못했어요",
                        detail = "검색어와 연결 상태를 확인한 뒤 다시 검색해 주세요.",
                        action = StogSurfaceAction.NONE,
                    )
                }
            } else if (searchState.candidates.isEmpty() && searchState.recentQueries.isEmpty()) {
                item {
                    StogStatePanel(
                        state = StogSurfaceState.EMPTY,
                        title = if (searchState.emptyResult) "검색 결과가 없어요" else "최근 검색 기록이 없어요",
                        detail = if (searchState.emptyResult) {
                            "다른 장소 이름이나 주소로 다시 검색해 보세요."
                        } else {
                            "장소나 주소를 입력하면 검색 기록과 결과가 여기에 보여요."
                        },
                    )
                }
            } else if (searchState.candidates.isEmpty()) {
                searchState.recentQueries.forEach { recentQuery ->
                    item(key = "recent-$recentQuery") {
                        RecentSearchRow(recentQuery)
                    }
                }
            }
            searchState.message?.takeIf { searchState.failure == null && !searchState.emptyResult }?.let { currentMessage ->
                item {
                    Text(
                        text = currentMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StogMuted,
                    )
                }
            }
            placeResultItems(
                candidates = searchState.candidates,
                baseUrl = baseUrl,
                savedExternalIds = searchState.savedExternalIds,
                savingExternalId = searchState.savingExternalId,
                onSelect = onSelectCandidate,
                onSave = { candidate ->
                    keyboardController?.hide()
                    tripSelectionSheetOpen = true
                    val selection = searchState.requestSave(candidate, accessToken)
                    searchState = selection.state
                    selection.loginRequiredCandidate?.let(onLoginRequired)
                },
            )
        }
    }

    val pendingCandidate = searchState.pendingCandidate
    if (tripSelectionSheetOpen && pendingCandidate != null) {
        ModalBottomSheet(
            onDismissRequest = { tripSelectionSheetOpen = false },
            sheetState = tripSelectionSheetState,
        ) {
            PendingPlaceTripSelection(
                candidate = pendingCandidate,
                availableTrips = availableTrips,
                loadingTrips = loadingTrips,
                tripLoadMessage = tripLoadMessage,
                onSelect = { trip -> saveCandidate(pendingCandidate, trip) },
                onCreateTrip = {
                    tripSelectionSheetOpen = false
                    onCreateTrip()
                },
            )
        }
    }
}

@Composable
private fun PendingPlaceTripSelection(
    candidate: PlaceSearchCandidate,
    availableTrips: List<TripSummary>,
    loadingTrips: Boolean,
    tripLoadMessage: String?,
    onSelect: (TripSummary) -> Unit,
    onCreateTrip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "저장할 여행 선택",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = candidate.name,
            color = StogMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (loadingTrips) {
            Text("여행 목록을 불러오는 중이에요.", color = StogMuted)
        }
        tripLoadMessage?.let { message ->
            Text(message, color = StogMuted)
        }
        availableTrips.forEach { trip ->
            OutlinedButton(
                onClick = { onSelect(trip) },
                enabled = !loadingTrips,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = trip.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!loadingTrips && tripLoadMessage == null) {
            TextButton(onClick = onCreateTrip) {
                Text("새 여행 만들기")
            }
        }
    }
}
@Composable
private fun PendingPlaceTripSelection(
    availableTrips: List<TripSummary>,
    loadingTrips: Boolean,
    tripLoadMessage: String?,
    onSelect: (TripSummary) -> Unit,
    onCreateTrip: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "장소를 담을 여행을 선택해주세요.",
            style = MaterialTheme.typography.titleMedium,
        )
        if (loadingTrips) {
            Text("여행 목록을 불러오는 중이에요.", color = StogMuted)
        }
        tripLoadMessage?.let { message ->
            Text(message, color = StogMuted)
        }
        availableTrips.forEach { trip ->
            OutlinedButton(
                onClick = { onSelect(trip) },
                enabled = !loadingTrips,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(trip.title)
            }
        }
        if (!loadingTrips && tripLoadMessage == null) {
            TextButton(onClick = onCreateTrip) {
                Text("새 여행 만들기")
            }
        }
    }
}
