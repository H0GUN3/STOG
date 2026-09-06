package com.stog.app.feature.space

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.stog.app.feature.plan.share_import.StoredShareImport
import com.stog.app.feature.stobee.StobeeChatEntry
import com.stog.app.ui.StogMapBottomSheet
import com.stog.app.ui.PermissionRequestDecision
import com.stog.app.ui.StogPermissionDialog
import com.stog.app.ui.StogPermissionPrompt
import com.stog.app.ui.StogPermissionPromptKind
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.decidePermissionRequest
import com.stog.app.ui.openStogPermissionSettings
import com.stog.app.ui.rememberStogMapBottomSheetState
import com.stog.app.ui.shouldShowStogPermissionRationale
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
internal fun SpaceMapScreen(
    baseUrl: String,
    accessToken: String?,
    nickname: String? = null,
    confirmedShareImports: List<StoredShareImport>,
    searchCandidates: List<PlaceSearchCandidate> = emptyList(),
    searchOverlayOpen: Boolean = false,
    initialState: MapScreenState = MapScreenState(),
    locationRefreshKey: Int = 0,
    onPlaceSearch: (MapScreenState) -> Unit,
    onSavePlace: (PlaceSearchCandidate) -> Unit,
    onLoginRequired: () -> Unit,
    onCapture: () -> Unit,
    onArchive: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenTravel: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenUser: () -> Unit,
    aiTripId: Long? = null,
    aiTripTitle: String? = null,
    onSelectStobeeTrip: (Long, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var locationPermissionGranted by remember {
        mutableStateOf(context.hasLocationPermission())
    }
    var locationCameraRequestKey by remember {
        mutableIntStateOf(if (locationPermissionGranted) 1 else 0)
    }
    var locationPermissionRequestAttempted by rememberSaveable {
        mutableStateOf(false)
    }
    var locationPermissionPrompt by remember {
        mutableStateOf<StogPermissionPrompt?>(null)
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val permissionGranted = context.hasLocationPermission()
        locationPermissionGranted = permissionGranted
        if (permissionGranted) locationCameraRequestKey++
    }
    fun requestLocationPermission() {
        val granted = context.hasLocationPermission()
        locationPermissionGranted = granted
        when (
            decidePermissionRequest(
                granted = granted,
                shouldShowRationale = locationPermissions.any {
                    context.shouldShowStogPermissionRationale(it)
                },
                requestAttempted = locationPermissionRequestAttempted,
            )
        ) {
            PermissionRequestDecision.ALREADY_GRANTED -> locationCameraRequestKey++
            PermissionRequestDecision.SHOW_RATIONALE -> {
                locationPermissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.RATIONALE,
                    title = "위치 권한이 필요해요",
                    detail = "현재 위치를 지도에 표시하려면 위치 권한이 필요해요. 지도 탐색은 권한 없이도 계속할 수 있어요.",
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
    var shellState by remember(initialState.shellState) {
        mutableStateOf(initialState.shellState)
    }
    val sheetState = rememberStogMapBottomSheetState(initialState.sheetLevel)
    val scope = rememberCoroutineScope()
    val placeClient = remember(baseUrl) { PlaceApiClient(baseUrl) }
    val cellClient = remember(baseUrl) { CellApiClient(baseUrl) }
    val placeRequestGate = remember { TripRequestGate() }
    val cellRequestGate = remember { TripRequestGate() }
    val mapContext = initialState.mapContext
    val discoveryCellTarget = initialState.discoveryCellTarget
    val returnToHomeOnPlaceDetailsClose = initialState.returnToHomeOnPlaceDetailsClose
    val returnToDiscoverOnCellClose = initialState.returnToDiscoverOnCellClose
    var placeDetailsState by remember(initialState.placeDetailsState) {
        mutableStateOf(initialState.placeDetailsState)
    }
    var cellDetailsState by remember(initialState.cellDetailsState) {
        mutableStateOf(initialState.cellDetailsState)
    }
    var preImeStobeeSheetLevel by remember {
        mutableStateOf<SheetLevel?>(null)
    }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(shellState.isAiGuideOpen, imeVisible) {
        val transition = resolveStobeeImeSheetTransition(
            aiGuideOpen = shellState.isAiGuideOpen,
            imeVisible = imeVisible,
            currentLevel = sheetState.settledLevel,
            rememberedLevel = preImeStobeeSheetLevel,
        )
        preImeStobeeSheetLevel = transition.rememberedLevel
        transition.targetLevel?.let { target ->
            sheetState.animateTo(target)
        }
    }

    LaunchedEffect(accessToken, mapContext) {
        cellRequestGate.invalidate()
        if (shellState.sheetKind == MapSheetKind.CELL_DETAILS) {
            cellDetailsState = CellDetailsState.Idle
        }
    }

    LaunchedEffect(Unit) {
        if (!locationPermissionGranted) {
            requestLocationPermission()
        }
    }

    LaunchedEffect(locationRefreshKey) {
        val permissionGranted = context.hasLocationPermission()
        locationPermissionGranted = permissionGranted
        locationCameraRequestKey = nextLocationCameraRequestKey(permissionGranted, locationCameraRequestKey)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val permissionGranted = context.hasLocationPermission()
                locationPermissionGranted = permissionGranted
                locationCameraRequestKey = nextLocationCameraRequestKey(permissionGranted, locationCameraRequestKey)
                if (permissionGranted && locationPermissionPrompt?.kind == StogPermissionPromptKind.SETTINGS) {
                    locationPermissionPrompt = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun showMenu(menu: MapMenu) {
        if (menu == MapMenu.HOME) {
            onOpenHome()
            return
        }
        if (menu == MapMenu.TRAVEL) {
            onOpenTravel()
            return
        }
        if (menu == MapMenu.SOCIAL) {
            onOpenDiscover()
            return
        }
        if (menu == MapMenu.RECOMMENDATIONS) {
            onOpenUser()
            return
        }
        placeRequestGate.invalidate()
        cellRequestGate.invalidate()
        placeDetailsState = PlaceDetailsState.Idle
        cellDetailsState = CellDetailsState.Idle
        shellState = shellState.selectMenu(menu)
        scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
    }

    fun showAiGuide() {
        placeRequestGate.invalidate()
        cellRequestGate.invalidate()
        placeDetailsState = PlaceDetailsState.Idle
        cellDetailsState = CellDetailsState.Idle
        shellState = shellState.openAiGuide()
        scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
    }

    fun showPlaceDetails(
        externalId: String,
        searchCandidate: PlaceSearchCandidate? = null,
    ) {
        placeRequestGate.invalidate()
        val requestVersion = placeRequestGate.capture()
        shellState = shellState.openPlaceDetails()
        scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
        if (searchCandidate != null) {
            val details = searchCandidate.toPlaceDetails()
            placeDetailsState = PlaceDetailsState.Loaded(
                details = details,
                distanceMeters = details.distanceFrom(context.lastKnownLocation()),
            )
            return
        }
        placeDetailsState = PlaceDetailsState.Loading
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    placeClient.details(externalId)
                }
            }.onSuccess { details ->
                placeRequestGate.applyIfCurrent(requestVersion) {
                    placeDetailsState = PlaceDetailsState.Loaded(
                        details = details,
                        distanceMeters = details.distanceFrom(context.lastKnownLocation()),
                    )
                }
            }.onFailure {
                placeRequestGate.applyIfCurrent(requestVersion) {
                    placeDetailsState = PlaceDetailsState.Failed(
                        "장소 상세 정보를 불러오지 못했어요.",
                    )
                }
            }
        }
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
                    locationPermissionLauncher.launch(locationPermissions)
                }
            },
        )
    }

    fun showCellDetails(cellId: String) {
        placeRequestGate.invalidate()
        val requestVersion = cellRequestGate.begin()
        shellState = shellState.openCellDetails()
        cellDetailsState = CellDetailsState.Loading
        scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val detail = cellClient.detail(cellId, accessToken) ?: error("cell response is invalid")
                    val photos = if (mapContext == MapContext.DISCOVERY) {
                        cellClient.allPhotos(cellId, accessToken)
                    } else if (!accessToken.isNullOrBlank()) {
                        cellClient.allPhotos(cellId, accessToken, mineOnly = true)
                    } else {
                        emptyList()
                    }
                    detail to photos
                }
            }.onSuccess { (detail, photos) ->
                cellRequestGate.applyIfCurrent(requestVersion) {
                    cellDetailsState = CellDetailsState.Loaded(detail, photos)
                }
            }.onFailure {
                cellRequestGate.applyIfCurrent(requestVersion) {
                    cellDetailsState = CellDetailsState.Failed("셀 정보를 다시 불러와주세요.")
                }
            }
        }
    }

    LaunchedEffect(discoveryCellTarget) {
        discoveryCellTarget?.let { showCellDetails(it.cellId) }
    }

    val backAction = resolveBackAction(
        BackContractState(
            imeVisible = imeVisible,
            sheetInTransition = sheetState.isInTransition,
            sheetLevel = sheetState.settledLevel,
            detailOpen = shellState.sheetKind == MapSheetKind.PLACE_DETAILS ||
                shellState.sheetKind == MapSheetKind.CELL_DETAILS,
            hasDestinationParent = false,
        ),
    )
    BackHandler(enabled = backAction != BackAction.System) {
        when (backAction) {
            BackAction.DismissIme -> keyboardController?.hide()
            BackAction.SettleSheet -> scope.launch {
                sheetState.animateTo(sheetState.settledLevel)
            }
            BackAction.CollapseSheet -> sheetState.settledLevel.backTarget()?.let { target ->
                scope.launch { sheetState.animateTo(target) }
            }
            BackAction.CloseDetail -> {
                placeRequestGate.invalidate()
                cellRequestGate.invalidate()
                if (
                    shellState.sheetKind == MapSheetKind.CELL_DETAILS &&
                    returnToDiscoverOnCellClose
                ) {
                    onOpenDiscover()
                } else if (
                    shellState.sheetKind == MapSheetKind.PLACE_DETAILS &&
                    returnToHomeOnPlaceDetailsClose
                ) {
                    onOpenHome()
                } else {
                    shellState = shellState.selectMenu(shellState.selectedMenu)
                }
            }
            BackAction.NavigateToParent,
            BackAction.System,
            -> Unit
        }
    }

    fun openSearchFromCurrentMap() {
        val detailsCanResume =
            shellState.sheetKind == MapSheetKind.PLACE_DETAILS &&
                placeDetailsState !is PlaceDetailsState.Loading
        onPlaceSearch(
            MapScreenState(
                shellState = if (detailsCanResume) {
                    shellState
                } else {
                    shellState.selectMenu(shellState.selectedMenu)
                },
                sheetLevel = sheetState.settledLevel,
                placeDetailsState = if (detailsCanResume) {
                    placeDetailsState
                } else {
                    PlaceDetailsState.Idle
                },
                cellDetailsState = cellDetailsState,
                returnToHomeOnPlaceDetailsClose = if (detailsCanResume) {
                    returnToHomeOnPlaceDetailsClose
                } else {
                    false
                },
            ),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (shouldShowMapNavigationBar(
                    sheetState.settledLevel,
                    shellState.sheetKind,
                    imeVisible,
                )
            ) {
                MapShellNavigationBar(
                    selectedMenu = shellState.selectedMenu,
                    aiGuideSelected = shellState.isAiGuideOpen,
                    onMenuSelected = ::showMenu,
                    onAiGuide = ::showAiGuide,
                )
            }
        },
    ) { innerPadding ->
        StogMapBottomSheet(
            state = sheetState,
            onSearch = ::openSearchFromCurrentMap,
            onSearchResultBack = ::openSearchFromCurrentMap,
            searchResultHeader = searchCandidates.isNotEmpty(),
            useSharedHeader = shellState.isAiGuideOpen,
            showBottomSheet = shellState.sheetKind != MapSheetKind.MENU,
            showTopAppBar = true,
            mapContent = { topPaddingPx ->
                GoogleMapView(
                    locationPermissionGranted = locationPermissionGranted,
                    locationCameraRequestKey = locationCameraRequestKey,
                    mapTopPaddingPx = topPaddingPx,
                    mapContext = mapContext,
                    discoveryCellTarget = discoveryCellTarget,
                    searchCandidates = searchCandidates,
                    searchOverlayOpen = searchOverlayOpen,
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    onCellSelected = ::showCellDetails,
                    onSearchMarkerSelected = { marker ->
                        showPlaceDetails(
                            externalId = marker.externalId,
                            searchCandidate = searchCandidates.firstOrNull { candidate ->
                                candidate.externalId == marker.externalId &&
                                    candidate.provenance == marker.provenance
                            },
                        )
                    },
                    onPoiSelected = ::showPlaceDetails,
                    onRequestLocationPermission = ::requestLocationPermission,
                    modifier = Modifier.matchParentSize(),
                )
            },
            sheetContent = {
                when (shellState.sheetKind) {
                    MapSheetKind.MENU -> Unit
                    MapSheetKind.AI_GUIDE -> StobeeChatEntry(
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        level = sheetState.settledLevel,
                        tripId = aiTripId,
                        tripTitle = aiTripTitle,
                        onSelectTrip = onSelectStobeeTrip,
                        onActivate = {
                            scope.launch {
                                sheetState.animateTo(SheetLevel.HalfExpanded)
                            }
                        },
                        onLoginRequired = onLoginRequired,
                        modifier = Modifier.fillMaxSize(),
                    )
                    MapSheetKind.PLACE_DETAILS -> {
                        PlaceDetailsSheet(
                            state = placeDetailsState,
                            baseUrl = baseUrl,
                            accessToken = accessToken,
                            level = sheetState.settledLevel,
                            onCollapse = {
                                scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
                            },
                            onSave = { details ->
                                onSavePlace(details.toSearchCandidate())
                            },
                            onClose = {
                                if (returnToHomeOnPlaceDetailsClose) {
                                    onOpenHome()
                                } else {
                                    shellState = shellState.selectMenu(shellState.selectedMenu)
                                    scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
                                }
                            },
                        )
                    }
                    MapSheetKind.CELL_DETAILS -> PlaceDetailsSheet(
                        state = placeDetailsState,
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        level = sheetState.settledLevel,
                        cellState = cellDetailsState,
                        mapContext = mapContext,
                        onCollapse = {
                            scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
                        },
                        onSave = {},
                        onClose = {
                            cellRequestGate.invalidate()
                            if (returnToDiscoverOnCellClose) {
                                onOpenDiscover()
                            } else {
                                shellState = shellState.selectMenu(shellState.selectedMenu)
                                scope.launch { sheetState.animateTo(SheetLevel.HalfExpanded) }
                            }
                        },
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        )
    }
}

internal data class StobeeImeSheetTransition(
    val rememberedLevel: SheetLevel?,
    val targetLevel: SheetLevel?,
)

internal fun resolveStobeeImeSheetTransition(
    aiGuideOpen: Boolean,
    imeVisible: Boolean,
    currentLevel: SheetLevel,
    rememberedLevel: SheetLevel?,
): StobeeImeSheetTransition = when {
    !aiGuideOpen -> StobeeImeSheetTransition(
        rememberedLevel = null,
        targetLevel = null,
    )
    imeVisible -> StobeeImeSheetTransition(
        rememberedLevel = rememberedLevel ?: currentLevel,
        targetLevel = SheetLevel.Expanded,
    )
    rememberedLevel != null -> StobeeImeSheetTransition(
        rememberedLevel = null,
        targetLevel = rememberedLevel,
    )
    else -> StobeeImeSheetTransition(
        rememberedLevel = null,
        targetLevel = null,
    )
}

internal fun shouldShowMapNavigationBar(
    sheetLevel: SheetLevel,
    sheetKind: MapSheetKind,
    imeVisible: Boolean,
): Boolean {
    val detailExpanded = sheetLevel == SheetLevel.Expanded &&
        (sheetKind == MapSheetKind.PLACE_DETAILS || sheetKind == MapSheetKind.CELL_DETAILS)
    return !detailExpanded && !(sheetKind == MapSheetKind.AI_GUIDE && imeVisible)
}

private data class CachedCellImage(
    val url: String,
    val bitmap: Bitmap,
)

private const val CELL_IMAGE_CACHE_MAX_BYTES = 8 * 1024 * 1024

@Composable
private fun GoogleMapView(
    mapContext: MapContext,
    discoveryCellTarget: MapCellTarget?,
    locationPermissionGranted: Boolean,
    locationCameraRequestKey: Int,
    mapTopPaddingPx: Int,
    searchCandidates: List<PlaceSearchCandidate>,
    searchOverlayOpen: Boolean,
    baseUrl: String,
    accessToken: String?,
    onCellSelected: (String) -> Unit,
    onSearchMarkerSelected: (PlaceMapMarker) -> Unit,
    onPoiSelected: (String) -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { MapView(context) }
    val cellClient = remember(baseUrl) { CellApiClient(baseUrl) }
    val cellViewportCoordinator = remember { CellViewportCoordinator() }
    val cellScope = rememberCoroutineScope()
    var cellLoadJob by remember { mutableStateOf<Job?>(null) }
    var renderedCells by remember { mutableStateOf<List<CellSummary>>(emptyList()) }
    var cellImageVersion by remember { mutableIntStateOf(0) }
    val cellImageCache = remember {
        object : LruCache<String, CachedCellImage>(CELL_IMAGE_CACHE_MAX_BYTES) {
            override fun sizeOf(key: String, value: CachedCellImage): Int =
                value.bitmap.allocationByteCount
        }
    }
    var cellLayerMessage by remember { mutableStateOf<String?>(null) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableStateOf(0f) }
    var lastCenteredRequestKey by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val resultBoundsPaddingPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val latestOnSearchMarkerSelected = rememberUpdatedState(onSearchMarkerSelected)
    val latestOnPoiSelected = rememberUpdatedState(onPoiSelected)
    val latestOnCellSelected = rememberUpdatedState(onCellSelected)
    val clusterMarkerTargets = remember { mutableMapOf<Marker, CellCluster>() }

    SideEffect {
        googleMap?.apply {
            setPadding(0, mapTopPaddingPx, 0, 0)
            setLocationLayerEnabled(context, locationPermissionGranted)
        }
    }

    DisposableEffect(
        googleMap,
        locationPermissionGranted,
        locationCameraRequestKey,
        searchCandidates.isEmpty(),
        discoveryCellTarget,
    ) {
        val map = googleMap
        val centersOnLocation = searchCandidates.isEmpty() && discoveryCellTarget == null
        val initialLocation = if (map != null && locationPermissionGranted && centersOnLocation) {
            context.lastKnownLocation()
        } else {
            null
        }
        val initialLocationTime = initialLocation?.time ?: Long.MIN_VALUE
        var centeredOnFreshLocation = false

        if (map != null && locationPermissionGranted && centersOnLocation) {
            if (
                initialLocation != null &&
                    needsLocationCameraRecenter(
                        requestKey = locationCameraRequestKey,
                        lastCenteredRequestKey = lastCenteredRequestKey,
                    )
            ) {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(initialLocation.latitude, initialLocation.longitude),
                        LOCATION_INITIAL_ZOOM,
                    ),
                )
                lastCenteredRequestKey = locationCameraRequestKey
            }

            map.setOnMyLocationChangeListener { location ->
                if (
                    !centeredOnFreshLocation &&
                        (
                            initialLocation == null ||
                                location.time > initialLocationTime
                            )
                ) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(location.latitude, location.longitude),
                            LOCATION_INITIAL_ZOOM,
                        ),
                    )
                    centeredOnFreshLocation = true
                    lastCenteredRequestKey = locationCameraRequestKey
                }
            }
        }
        onDispose {
            map?.setOnMyLocationChangeListener(null)
        }
    }

    DisposableEffect(googleMap) {
        val map = googleMap
        map?.setOnPoiClickListener { poi ->
            latestOnPoiSelected.value(poi.placeId)
        }
        onDispose {
            map?.setOnPoiClickListener(null)
        }
    }

    val cellLayerVisible = shouldShowCellLayer(mapContext, searchOverlayOpen)
    fun mapCells(): List<CellSummary> = cellViewportCoordinator.visibleCells.filter {
        shouldRenderCellInMap(mapContext, it)
    }

    DisposableEffect(googleMap, accessToken, mapContext, cellLayerVisible) {
        val map = googleMap
        cellViewportCoordinator.onAuthorizationChanged(accessToken)
        cellLoadJob?.cancel()
        cellImageCache.evictAll()
        cellImageVersion++
        renderedCells = emptyList()
        cellLayerMessage = null
        if (map != null && cellLayerVisible) {
            fun requestVisibleCells() {
                currentZoom = map.cameraPosition.zoom
                val viewport = runCatching {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    CellViewport(
                        southWestLatitude = bounds.southwest.latitude,
                        southWestLongitude = bounds.southwest.longitude,
                        northEastLatitude = bounds.northeast.latitude,
                        northEastLongitude = bounds.northeast.longitude,
                        zoom = map.cameraPosition.zoom,
                    )
                }.getOrNull() ?: return
                val request = cellViewportCoordinator.onCameraIdle(viewport, SystemClock.elapsedRealtime())
                cellLoadJob?.cancel()
                if (request == null) {
                    renderedCells = mapCells()
                    cellLayerMessage = null
                    return
                }
                cellLoadJob = cellScope.launch {
                    delay(CELL_VIEWPORT_DEBOUNCE_MILLIS)
                    if (!cellViewportCoordinator.isDue(request, SystemClock.elapsedRealtime())) return@launch
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val page = cellClient.summaries(request.viewport, accessToken)
                            page
                        }
                    }.onSuccess { page ->
                        if (cellViewportCoordinator.applyResult(request, page.cells)) {
                            renderedCells = mapCells()
                            cellLayerMessage = if (page.ignoredMalformedCellCount > 0) {
                                "일부 셀 정보를 표시하지 못했어요. 지도를 다시 움직여주세요."
                            } else {
                                null
                            }
                        }
                    }.onFailure { error ->
                        if (cellViewportCoordinator.clearPendingRequest(request)) {
                            cellLayerMessage = if (error is CellAuthenticationException) {
                                "로그인 상태가 바뀌어 내 여행 표시를 정리했어요."
                            } else {
                                "셀 정보를 불러오지 못했어요. 지도를 다시 움직여주세요."
                            }
                        }
                    }
                }
            }
            map.setOnCameraIdleListener { requestVisibleCells() }
            requestVisibleCells()
        }
        onDispose {
            cellLoadJob?.cancel()
            map?.setOnCameraIdleListener(null)
        }
    }

    DisposableEffect(googleMap, renderedCells, cellLayerVisible, currentZoom) {
        val map = googleMap
        val polygons = mutableListOf<Polygon>()
        val polygonCells = mutableMapOf<Polygon, CellSummary>()
        if (map != null && cellLayerVisible) {
            renderedCells.filter { it.rendersPolygonAt(currentZoom) }.forEach { cell ->
                map.addPolygon(
                    PolygonOptions()
                        .addAll(cell.boundary.map { point -> LatLng(point.latitude, point.longitude) })
                        .strokeColor(StogBorder.copy(alpha = 0.9f).toArgb())
                        .strokeWidth(4f)
                        .fillColor(cellPolygonFillColor(cellBackgroundForMap(mapContext, cell)))
                        .clickable(true),
                ).also { polygon ->
                    polygons += polygon
                    polygonCells[polygon] = cell
                }
            }
            map.setOnPolygonClickListener { polygon ->
                polygonCells[polygon]?.let { cell -> latestOnCellSelected.value(cell.cellId) }
            }
        }
        onDispose {
            polygons.forEach(Polygon::remove)
            map?.setOnPolygonClickListener(null)
        }
    }

    LaunchedEffect(renderedCells, cellLayerVisible, mapContext) {
        if (!cellLayerVisible) return@LaunchedEffect
        renderedCells
            .mapNotNull { cell ->
                cellImageUrl(mapContext, cell)?.let { url -> cell to url }
            }
            .filter { (cell, url) -> cellImageCache.get(cell.cellId)?.url != url }
            .forEach { cell ->
                launch {
                    val image = withContext(Dispatchers.IO) {
                        downloadCellImage(cell.second)?.let { bitmap ->
                            clipCellImage(bitmap, cell.first.boundary)
                        }
                    }
                    if (image != null) {
                        cellImageCache.put(
                            cell.first.cellId,
                            CachedCellImage(cell.second, image),
                        )
                        cellImageVersion++
                    }
                }
            }
    }

    DisposableEffect(googleMap, renderedCells, cellImageVersion, cellLayerVisible) {
        val map = googleMap
        val overlays = mutableListOf<GroundOverlay>()
        if (map != null && cellLayerVisible) {
            renderedCells.forEach { cell ->
                val source = cellImageCache.get(cell.cellId)
                    ?.takeIf { it.url == cellImageUrl(mapContext, cell) }
                    ?.bitmap
                    ?: return@forEach
                map.addGroundOverlay(
                    GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromBitmap(source))
                        .positionFromBounds(cellImageBounds(cell))
                        .zIndex(1f),
                )?.let(overlays::add)
            }
        }
        onDispose {
            overlays.forEach(GroundOverlay::remove)
        }
    }

    DisposableEffect(googleMap, renderedCells, currentZoom, cellLayerVisible, mapContext) {
        val map = googleMap
        val markers = mutableListOf<Marker>()
        clusterMarkerTargets.clear()
        if (map != null && cellLayerVisible && currentZoom <= CELL_POLYGON_MINIMUM_ZOOM) {
            clusterCells(renderedCells).forEach { cluster ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(cluster.centroid.latitude, cluster.centroid.longitude))
                        .title(
                            if (cluster.landmarkCount > 0L) {
                                "명소 ${cluster.landmarkCount}개 · 셀 ${cluster.cellCount}개"
                            } else {
                                "셀 ${cluster.cellCount}개"
                            },
                        ),
                )?.let { marker ->
                    markers += marker
                    clusterMarkerTargets[marker] = cluster
                }
            }
        }
        onDispose {
            markers.forEach(Marker::remove)
            clusterMarkerTargets.clear()
        }
    }

    DisposableEffect(
        googleMap,
        searchCandidates,
        discoveryCellTarget,
        resultBoundsPaddingPx,
    ) {
        val map = googleMap
        val markers = mutableListOf<Marker>()
        val markerResults = mutableMapOf<Marker, PlaceMapMarker>()
        val markerCells = mutableSetOf<Marker>()
        val resultMarkers = placeMarkersFor(searchCandidates)

        if (map != null) {
            discoveryCellTarget?.let { target ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(target.latitude, target.longitude))
                        .title("장소 정보"),
                )?.let { marker ->
                    markers += marker
                    markerCells += marker
                }
            }
            resultMarkers.forEach { candidate ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(candidate.latitude, candidate.longitude))
                        .title(candidate.name),
                )?.let { marker ->
                    markers += marker
                    markerResults[marker] = candidate
                }
            }
            when {
                resultMarkers.isEmpty() && discoveryCellTarget != null -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(discoveryCellTarget.latitude, discoveryCellTarget.longitude),
                        LOCATION_INITIAL_ZOOM,
                    ),
                )
                resultMarkers.isEmpty() -> Unit
                resultMarkers.size == 1 -> map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(resultMarkers.first().latitude, resultMarkers.first().longitude),
                        LOCATION_INITIAL_ZOOM,
                    ),
                )
                else -> {
                    val bounds = LatLngBounds.Builder().apply {
                        resultMarkers.forEach { marker ->
                            include(LatLng(marker.latitude, marker.longitude))
                        }
                    }.build()
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds, resultBoundsPaddingPx),
                    )
                }
            }
            map.setOnMarkerClickListener { marker ->
                clusterMarkerTargets[marker]?.let { cluster ->
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(cluster.centroid.latitude, cluster.centroid.longitude),
                            (currentZoom + 2f).coerceAtMost(16f),
                        ),
                    )
                    true
                } ?: if (markerCells.contains(marker)) {
                    discoveryCellTarget?.let { latestOnCellSelected.value(it.cellId) }
                    true
                } else markerResults[marker]?.let { result ->
                    latestOnSearchMarkerSelected.value(result)
                    true
                } ?: false
            }
        }

        onDispose {
            markers.forEach(Marker::remove)
            map?.setOnMarkerClickListener(null)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = {
                mapView.apply {
                    onCreate(Bundle())
                    getMapAsync { map ->
                        map.uiSettings.isMapToolbarEnabled = false
                        googleMap = map
                        mapReady = true
                    }
                }
            },
        )
        if (!mapReady) {
            StogStatePanel(
                state = StogSurfaceState.LOADING,
                title = "Google 지도를 불러오는 중",
                detail = "지도와 현재 화면 영역을 준비하고 있어요.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .zIndex(1f),
            )
        } else if (!locationPermissionGranted) {
            StogStatePanel(
                state = StogSurfaceState.PERMISSION_DENIED,
                title = "위치 권한이 꺼져 있어요",
                detail = "지도 탐색은 계속할 수 있고, 허용하면 현재 위치를 한 번 표시해요.",
                actionLabel = "위치 권한 다시 요청",
                onAction = onRequestLocationPermission,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .zIndex(1f),
            )
        }
        if (cellLayerVisible) {
            cellLayerMessage?.let { message ->
            StogStatePanel(
                state = StogSurfaceState.ERROR,
                title = "셀 정보를 표시하지 못했어요",
                detail = message,
                action = StogSurfaceAction.NONE,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .zIndex(2f),
            )
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
}

internal const val LOCATION_INITIAL_ZOOM = 15f

internal fun nextLocationCameraRequestKey(
    permissionGranted: Boolean,
    currentKey: Int,
): Int = if (permissionGranted) currentKey + 1 else currentKey

internal fun needsLocationCameraRecenter(
    requestKey: Int,
    lastCenteredRequestKey: Int,
): Boolean = requestKey != lastCenteredRequestKey

private fun cellPolygonFillColor(background: CellBackground): Int = when (background) {
    CellBackground.EMPTY -> StogCanvas.copy(alpha = 0.24f).toArgb()
    CellBackground.VISITED -> StogYellow.copy(alpha = 0.28f).toArgb()
    CellBackground.HOT -> StogYellow.copy(alpha = 0.50f).toArgb()
}

private fun Context.lastKnownLocation(): Location? {
    if (!hasLocationPermission()) return null

    val locationManager = getSystemService(LocationManager::class.java)
    return listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )
        .mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
        }
        .maxByOrNull { location -> location.time }
}

internal fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

private fun PlaceDetails.distanceFrom(location: Location?): Float? {
    val placeLatitude = latitude ?: return null
    val placeLongitude = longitude ?: return null
    val currentLocation = location ?: return null
    return FloatArray(1).also { result ->
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            placeLatitude,
            placeLongitude,
            result,
        )
    }.first()
}

private fun GoogleMap.setLocationLayerEnabled(
    context: Context,
    enabled: Boolean,
) {
    val shouldEnable = enabled && context.hasLocationPermission()
    try {
        isMyLocationEnabled = shouldEnable
        uiSettings.isMyLocationButtonEnabled = shouldEnable
    } catch (_: SecurityException) {
        isMyLocationEnabled = false
        uiSettings.isMyLocationButtonEnabled = false
    }
}
