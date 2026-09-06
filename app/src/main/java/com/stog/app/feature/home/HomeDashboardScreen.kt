package com.stog.app.feature.home

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stog.app.feature.plan.trip.TripCardData
import com.stog.app.feature.record.SetLogLocationAcquisition
import com.stog.app.feature.record.SetLogLocationFix
import com.stog.app.feature.record.SetLogLocationProvider
import com.stog.app.feature.record.homeRecommendationLocationTuning
import com.stog.app.feature.space.EventApiClient
import com.stog.app.feature.space.EventSearchCandidate
import com.stog.app.feature.space.PlaceApiClient
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.MapMenu
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.StogMainScaffold
import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.hasLocationPermission
import com.stog.app.feature.space.loadTripCards
import com.stog.app.ui.PermissionRequestDecision
import com.stog.app.ui.StogPermissionDialog
import com.stog.app.ui.StogPermissionPrompt
import com.stog.app.ui.StogPermissionPromptKind
import com.stog.app.ui.decidePermissionRequest
import com.stog.app.ui.openStogPermissionSettings
import com.stog.app.ui.shouldShowStogPermissionRationale
import com.stog.app.ui.theme.StogMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDashboardScreen(
    baseUrl: String,
    accessToken: String?,
    nickname: String?,
    onLoginRequired: () -> Unit,
    onSearch: () -> Unit,
    onOpenTravel: () -> Unit,
    onCreateTrip: () -> Unit = {},
    onOpenTripDetail: (TripSummary) -> Unit = {},
    onOpenDiscover: () -> Unit,
    onOpenUser: () -> Unit,
    onOpenStobee: () -> Unit,
    onCapture: () -> Unit,
    onSavePlace: (PlaceSearchCandidate) -> Unit,
    onOpenPlace: (PlaceSearchCandidate) -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    locationRefreshKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val planning = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val places = remember(baseUrl) { PlaceApiClient(baseUrl) }
    val events = remember(baseUrl) { EventApiClient(baseUrl) }
    var locationPermissionGranted by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    var locationPermissionRefreshKey by remember {
        mutableIntStateOf(0)
    }
    var locationPermissionRequestAttempted by rememberSaveable {
        mutableStateOf(false)
    }
    var locationPermissionPrompt by remember {
        mutableStateOf<StogPermissionPrompt?>(null)
    }
    var summary by remember(accessToken) { mutableStateOf<com.stog.app.feature.space.HomeSummary?>(null) }
    var cards by remember(accessToken) { mutableStateOf<List<TripCardData>>(emptyList()) }
    var recommendations by remember(accessToken) { mutableStateOf<List<PlaceSearchCandidate>>(emptyList()) }
    var eventRecommendations by remember(accessToken) {
        mutableStateOf<List<EventSearchCandidate>>(emptyList())
    }
    var recommendationsLoading by remember(accessToken) { mutableStateOf(false) }
    var recommendationLocationAvailable by remember(accessToken) { mutableStateOf(false) }
    var loading by remember(accessToken) { mutableStateOf(accessToken != null) }
    var selectedTrip by remember { mutableStateOf<TripSummary?>(null) }

    fun requestHomeLocationPermission() {
        val granted = context.hasLocationPermission()
        locationPermissionGranted = granted
        when (
            decidePermissionRequest(
                granted = granted,
                shouldShowRationale = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ).any { context.shouldShowStogPermissionRationale(it) },
                requestAttempted = locationPermissionRequestAttempted,
            )
        ) {
            PermissionRequestDecision.ALREADY_GRANTED -> locationPermissionRefreshKey++
            PermissionRequestDecision.SHOW_RATIONALE -> {
                locationPermissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.RATIONALE,
                    title = "위치 권한이 필요해요",
                    detail = "현재 위치 주변의 추천 장소를 보여드리려면 위치 권한이 필요해요. 권한 없이도 나머지 홈 기능은 사용할 수 있어요.",
                )
            }
            PermissionRequestDecision.OPEN_SETTINGS -> {
                locationPermissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.SETTINGS,
                    title = "위치 권한을 켜 주세요",
                    detail = "위치 권한이 계속 거부되어 앱에서 다시 요청할 수 없어요. Android 앱 설정에서 위치 권한을 허용해 주세요.",
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationPermissionGranted = context.hasLocationPermission()
                locationPermissionRefreshKey++
                if (locationPermissionGranted && locationPermissionPrompt?.kind == StogPermissionPromptKind.SETTINGS) {
                    locationPermissionPrompt = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(accessToken) {
        if (accessToken == null) {
            summary = null
            cards = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            withContext(Dispatchers.IO) { planning.home(accessToken) }
        }.onSuccess { loaded ->
            summary = loaded
            cards = loadHomeCards(
                planning,
                accessToken,
                loaded.trips.filter(::isHomeTripVisible),
            )
        }
        loading = false
    }

    LaunchedEffect(accessToken, locationRefreshKey, locationPermissionRefreshKey) {
        if (!locationPermissionGranted) {
            recommendations = emptyList()
            eventRecommendations = emptyList()
            recommendationLocationAvailable = false
            recommendationsLoading = false
            return@LaunchedEffect
        }
        recommendationsLoading = true
        val location = context.homeLocationFix()
        recommendationLocationAvailable = location != null
        val loadedRecommendations = location?.let { fix ->
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val placesRequest = async {
                        runCatching { places.nearby(fix.latitude, fix.longitude) }
                            .getOrDefault(emptyList())
                    }
                    val eventsRequest = async {
                        runCatching { events.nearby(fix.latitude, fix.longitude) }
                            .getOrDefault(emptyList())
                    }
                    placesRequest.await() to eventsRequest.await()
                }
            }
        }
        recommendations = loadedRecommendations?.first.orEmpty()
        eventRecommendations = loadedRecommendations?.second.orEmpty()
        recommendationsLoading = false
    }

    locationPermissionPrompt?.let { prompt ->
        StogPermissionDialog(
            prompt = prompt,
            onDismiss = { locationPermissionPrompt = null },
            onPrimary = {
                locationPermissionPrompt = null
                if (prompt.kind == StogPermissionPromptKind.SETTINGS) {
                    context.openStogPermissionSettings()
                } else {
                    locationPermissionRequestAttempted = true
                    onRequestLocationPermission()
                }
            },
        )
    }

    StogMainScaffold(
        selectedMenu = MapMenu.HOME,
        onSearch = onSearch,
        onMenuSelected = { menu ->
            when (menu) {
                MapMenu.HOME -> Unit
                MapMenu.TRAVEL -> onOpenTravel()
                MapMenu.SOCIAL -> onOpenDiscover()
                MapMenu.RECOMMENDATIONS -> onOpenUser()
            }
        },
        onOpenStobee = onOpenStobee,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = (
                        com.stog.app.ui.StogUiContract.HomeCameraActionSizeDp +
                            com.stog.app.ui.StogUiContract.ScreenGutterDp +
                            com.stog.app.ui.StogUiContract.MapOverlayGapDp
                        ).dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
            item {
                HomeHero(nickname = nickname)
            }
            item {
                summary?.let {
                    HomeMetricRow(
                        monthlyTrips = it.monthlyTripCount,
                        visitedCells = it.visitedCellCount,
                        savedPlaces = it.savedPlaceCount,
                        receivedLikes = it.monthlyReceivedLikeCount,
                    )
                }
            }
            item {
                HomeSectionTitle("지금 가볼 만한 곳", onMore = onOpenDiscover)
            }
            if (recommendationsLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (recommendations.isEmpty() && eventRecommendations.isEmpty()) {
                item {
                    HomeEmptyNotice(
                        text = when {
                            !locationPermissionGranted ->
                                "주변 관광지와 행사를 보려면 위치 권한을 허용해주세요."
                            !recommendationLocationAvailable ->
                                "현재 위치를 확인하지 못했어요."
                            else ->
                                "현재 위치에서 주변 관광지나 행사를 찾지 못했어요."
                        },
                        actionLabel = if (locationPermissionGranted) null else "위치 권한 허용",
                        onAction = ::requestHomeLocationPermission,
                    )
                }
            } else {
                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recommendations) { candidate ->
                            HomeRecommendationCard(
                                candidate = candidate,
                                baseUrl = baseUrl,
                                onSave = { onSavePlace(candidate) },
                                onOpen = { onOpenPlace(candidate) },
                            )
                        }
                        items(eventRecommendations) { event ->
                            HomeEventCard(event = event)
                        }
                    }
                }
            }
            item {
                HomeSectionTitle(
                    title = "내 여행",
                    onMore = onOpenTravel,
                )
            }
            when {
                loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                accessToken == null -> item {
                    HomeEmptyNotice(
                        text = "로그인하면 내 여행을 한눈에 볼 수 있어요.",
                        actionLabel = "로그인",
                        onAction = onLoginRequired,
                    )
                }
                cards.isEmpty() -> item {
                    HomeCreateTripCard(
                        onClick = onCreateTrip,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                else -> items(cards, key = { it.trip.id }) { card ->
                    HomeTripCard(
                        card = card,
                        onClick = { selectedTrip = card.trip },
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            }
            HomeCaptureAction(
                onClick = onCapture,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(com.stog.app.ui.StogUiContract.ScreenGutterDp.dp),
            )
        }
    }

    selectedTrip?.let { trip ->
        HomeTripItinerarySheet(
            baseUrl = baseUrl,
            accessToken = accessToken,
            trip = trip,
            onDismiss = { selectedTrip = null },
            onOpenDetail = {
                selectedTrip = null
                onOpenTripDetail(trip)
            },
        )
    }
}

private suspend fun loadHomeCards(
    planning: PlanningApiClient,
    accessToken: String,
    trips: List<TripSummary>,
): List<TripCardData> = loadTripCards(planning, accessToken, trips)

@Composable
private fun HomeEmptyNotice(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text, color = StogMuted)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

private suspend fun Context.homeLocationFix(): SetLogLocationFix? =
    suspendCancellableCoroutine { continuation ->
        val cancellation = SetLogLocationProvider(
            this,
            homeRecommendationLocationTuning(),
        ).acquire { acquisition ->
            if (continuation.isActive) {
                continuation.resume(
                    (acquisition as? SetLogLocationAcquisition.Accepted)?.fix,
                )
            }
        }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }
