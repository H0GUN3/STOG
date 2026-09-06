package com.stog.app.feature.record

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stog.app.BuildConfig
import com.stog.app.R
import com.stog.app.core.database.PendingSetLogEntity
import com.stog.app.core.database.SetLogDeliveryQueue
import com.stog.app.core.database.SetLogEnqueueResult
import com.stog.app.core.database.SetLogOutboxState
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.plan.trip.TripCardImage
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.PermissionRequestDecision
import com.stog.app.ui.StogPermissionDialog
import com.stog.app.ui.StogPermissionPrompt
import com.stog.app.ui.StogPermissionPromptKind
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.decidePermissionRequest
import com.stog.app.ui.openStogPermissionSettings
import com.stog.app.ui.shouldShowStogPermissionRationale
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SET_LOG_CAMERA_LOG_TAG = "STOG.Camera"
private const val RECORD_ENTRY_CARD_HEIGHT_DP = 80

private enum class SetLogPermissionPromptTarget {
    CAMERA,
    LOCATION,
    MEDIA_LOCATION,
}

private fun setLogCameraLog(message: String) {
    if (!BuildConfig.DEBUG) return
    try {
        Log.d(SET_LOG_CAMERA_LOG_TAG, message)
    } catch (_: RuntimeException) {
        // Host JVM tests do not provide Android's Log implementation.
    }
}

@Composable
internal fun PhotoCaptureScreen(
    baseUrl: String,
    accessToken: String?,
    userId: Long?,
    onBack: () -> Unit,
    onAuthenticationRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val planning = remember(baseUrl) { PlanningApiClient(baseUrl) }
    var trips by remember(accessToken) { mutableStateOf<List<TripSummary>>(emptyList()) }
    var loading by remember(accessToken) { mutableStateOf(!accessToken.isNullOrBlank()) }
    var loadFailed by remember(accessToken) { mutableStateOf(false) }
    var selectedTripId by rememberSaveable(accessToken, userId) { mutableStateOf<Long?>(null) }
    var selectedTripTitle by rememberSaveable(accessToken, userId) { mutableStateOf<String?>(null) }
    var dailyCreating by rememberSaveable(accessToken, userId) { mutableStateOf(false) }
    var dailyCreationError by rememberSaveable(accessToken, userId) { mutableStateOf<String?>(null) }

    fun createDailySession() {
        if (dailyCreating) return
        val token = accessToken ?: return
        dailyCreating = true
        dailyCreationError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    planning.createTrip(
                        accessToken = token,
                        title = "일상",
                        activityType = "etc",
                    )
                }
            }.onSuccess { created ->
                selectedTripId = created.id
                selectedTripTitle = created.title
            }.onFailure {
                dailyCreationError = "일상 기록을 준비하지 못했어요. 잠시 후 다시 시도해 주세요."
            }
            dailyCreating = false
        }
    }

    LaunchedEffect(accessToken, userId) {
        if (accessToken.isNullOrBlank() || userId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadFailed = false
        runCatching { withContext(Dispatchers.IO) { planning.listTrips(accessToken) } }
            .onSuccess { loaded ->
                trips = loaded
            }
            .onFailure { loadFailed = true }
        loading = false
    }

    if (accessToken.isNullOrBlank() || userId == null) {
        BackHandler(onBack = onBack)
        PhotoRecordScaffold("사진 기록", onBack, modifier) {
            item {
                StogStatePanel(
                    state = StogSurfaceState.AUTH_EXPIRED,
                    title = "로그인이 필요해요",
                    detail = "로그인한 계정과 여행을 확인한 뒤 기록을 시작해요.",
                    actionLabel = "로그인",
                    onAction = onAuthenticationRequired,
                )
            }
        }
        return
    }
    if (loading || loadFailed) {
        BackHandler(onBack = onBack)
        PhotoRecordScaffold("사진 기록", onBack, modifier) {
            item {
                StogStatePanel(
                    state = if (loading) StogSurfaceState.LOADING else StogSurfaceState.OFFLINE,
                    title = if (loading) "여행을 확인하는 중" else "여행 목록을 불러오지 못했어요",
                    detail = "활성 또는 휴면 여행을 확인한 뒤 카메라를 열어요.",
                    action = StogSurfaceAction.NONE,
                )
            }
        }
        return
    }

    val preflight = setLogTripPreflight(trips)
    if (selectedTripId == null) {
        BackHandler(onBack = onBack)
        PhotoRecordScaffold("사진 기록", onBack, modifier) {
            item {
                DailyRecordEntryCard(
                    creating = dailyCreating,
                    onClick = ::createDailySession,
                )
            }
            dailyCreationError?.let { message ->
                item {
                    StogStatePanel(
                        state = StogSurfaceState.OFFLINE,
                        title = "일상 기록을 시작하지 못했어요",
                        detail = message,
                    )
                }
            }
            item {
                Text(
                    "여행 기록",
                    color = StogInk,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            when (preflight) {
                SetLogTripPreflight.NoActiveTrip -> item {
                    StogStatePanel(
                        state = StogSurfaceState.EMPTY,
                        title = "기록할 여행이 없어요",
                        detail = "여행을 기록하려면 내 여행에서 여행을 먼저 만들어 주세요.",
                    )
                }
                is SetLogTripPreflight.Selected -> item {
                    TripRecordEntryButton(
                        trip = preflight.trip,
                        onClick = {
                            selectedTripId = preflight.trip.id
                            selectedTripTitle = preflight.trip.title
                        },
                    )
                }
                is SetLogTripPreflight.Choose -> preflight.trips.forEach { trip ->
                    item(key = trip.id) {
                        TripRecordEntryButton(
                            trip = trip,
                            onClick = {
                                selectedTripId = trip.id
                                selectedTripTitle = trip.title
                            },
                        )
                    }
                }
            }
        }
        return
    }

    val selected = selectedTripId ?: return
    OwnedSetLogFlow(
        baseUrl = baseUrl,
        accessToken = accessToken,
        userId = userId,
        tripId = selected,
        tripTitle = selectedTripTitle
            ?: trips.firstOrNull { it.id == selected }?.title
            ?: "여행 기록",
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun DailyRecordEntryCard(
    creating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = !creating,
        modifier = modifier
            .fillMaxWidth()
            .height(RECORD_ENTRY_CARD_HEIGHT_DP.dp)
            .testTag("daily_record_entry")
            .semantics {
                contentDescription = if (creating) "일상 기록 준비 중" else "일상 기록"
                stateDescription = if (creating) "준비 중" else "기록 시작 가능"
                if (creating) liveRegion = LiveRegionMode.Polite
                role = Role.Button
            },
        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        colors = recordCardColors(),
        border = RecordCardBorder,
        elevation = CardDefaults.cardElevation(
            defaultElevation = RecordCardElevation,
            disabledElevation = RecordCardElevation,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (creating) "일상 기록을 준비하는 중..." else "일상",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "여행이 아니어도 지금을 기록해요",
                style = MaterialTheme.typography.bodySmall,
                color = StogMuted,
            )
        }
    }
}

@Composable
private fun TripRecordEntryButton(
    trip: TripSummary,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(RECORD_ENTRY_CARD_HEIGHT_DP.dp)
            .testTag("set_log_trip:${trip.id}"),
        shape = RecordCardShape,
        colors = recordCardColors(),
        border = RecordCardBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = RecordCardElevation),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TripCardImage(
                url = trip.coverImageUrl,
                fallbackRes = R.drawable.stog_travel_record,
                contentDescription = trip.title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    trip.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (trip.mode == "active") "진행 중인 여행" else "잠시 멈춘 여행",
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OwnedSetLogFlow(
    baseUrl: String,
    accessToken: String,
    userId: Long,
    tripId: Long,
    tripTitle: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val accountId = userId.toString()
    val derivatives = remember(context) { PhotoDerivativeStore(context) }
    val locationProvider = remember(context) { SetLogLocationProvider(context) }
    val api = remember(baseUrl) { PhotoApiClient(baseUrl) }
    val queue = remember(context) { SetLogDeliveryQueue(context) }
    val clock = remember { Clock.systemUTC() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val shutterLocationGate = remember { SetLogShutterLocationGate() }
    var state by remember(accountId, userId, tripId) {
        mutableStateOf(SetLogCaptureState(accountId, userId, tripId))
    }
    var capabilities by remember { mutableStateOf<SetLogCameraCapabilities?>(null) }
    var adapter by remember { mutableStateOf<SetLogCameraAdapter?>(null) }
    var prepared by remember { mutableStateOf<PreparedPhoto?>(null) }
    var locationFeedback by remember { mutableStateOf(initialLocationFeedback(context)) }
    var mediaLocationGranted by remember { mutableStateOf(context.hasMediaLocationPermission()) }
    var lastBoundToken by remember { mutableStateOf<SetLogCaptureToken?>(null) }
    var pendingPermissionStep by remember { mutableStateOf<SetLogEntryPermissionStep?>(null) }
    var permissionRequestId by remember { mutableStateOf(0) }
    var cameraPermissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var locationPermissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var mediaLocationPermissionRequestAttempted by rememberSaveable { mutableStateOf(false) }
    var permissionPrompt by remember { mutableStateOf<StogPermissionPrompt?>(null) }
    var permissionPromptTarget by remember { mutableStateOf<SetLogPermissionPromptTarget?>(null) }
    var galleryAfterPermissionPrompt by remember { mutableStateOf(false) }
    var requestEntryPermissionStep: () -> Unit = {}
    var requestLocationSettings: () -> Unit = {}
    var requestPermissionSettings: () -> Unit = {}

    fun openConfirmationIfCurrent(token: SetLogCaptureToken) {
        if (state.activeToken == token && state.stage == SetLogCaptureStage.DRAFT_READY) {
            state = reduceSetLogCapture(state, SetLogCaptureEvent.ConfirmationOpened).state
        }
    }

    fun previewPlace(draft: SetLogDraft) {
        val coordinates = draft.snapshot.coordinates ?: return
        val token = draft.snapshot.captureToken
        scope.launch {
            val preview = runCatching {
                withContext(Dispatchers.IO) {
                    api.previewPlace(accessToken, coordinates, draft.snapshot.accuracyMeters)
                }
            }.fold(
                onSuccess = { result ->
                    if (result.status == "matched" && result.placeId != null && !result.placeName.isNullOrBlank()) {
                        SetLogPlacePreview.Matched(result.placeId, result.placeName)
                    } else {
                        SetLogPlacePreview.NoMatch
                    }
                },
                onFailure = { SetLogPlacePreview.Failed((it as? PhotoRequestException)?.code ?: "PLACE_PREVIEW_PENDING") },
            )
            state = reduceSetLogCapture(state, SetLogCaptureEvent.PlacePreviewChanged(token, preview)).state
        }
    }

    fun acquireCameraLocation(token: SetLogCaptureToken) {
        val request = shutterLocationGate.begin(token) ?: return
        locationFeedback = SetLogLocationFeedback.ACQUIRING
        val cancellation = locationProvider.acquire(
            recent = SetLogRecentLocationCache.get(
                accountId = accountId,
                tripId = tripId.toString(),
                userId = userId.toString(),
            ),
        ) { acquisition ->
            mainExecutor.execute {
                if (!shutterLocationGate.deliver(request, state.activeToken, state.stage)) {
                    setLogCameraLog("location callback ignored by shutter gate")
                    return@execute
                }
                val hadDraftLocation = state.draft?.snapshot?.coordinates
                state = reduceSetLogCapture(
                    state,
                    SetLogCaptureEvent.LocationUpdated(token, acquisition),
                ).state
                when (acquisition) {
                    is SetLogLocationAcquisition.Accepted -> {
                        locationFeedback = SetLogLocationFeedback.AVAILABLE
                        if (
                            hadDraftLocation == null &&
                            state.draft?.snapshot?.coordinates != null
                        ) {
                            state.draft?.let(::previewPlace)
                        }
                    }
                    is SetLogLocationAcquisition.Rejected -> {
                        locationFeedback = when (acquisition.reason) {
                            SetLogLocationRejection.PROVIDER_DISABLED ->
                                SetLogLocationFeedback.PROVIDER_DISABLED
                            SetLogLocationRejection.PERMISSION_DENIED,
                            SetLogLocationRejection.PERMISSION_REVOKED,
                            SetLogLocationRejection.REQUEST_DENIED,
                            -> SetLogLocationFeedback.PERMISSION_REQUIRED
                            SetLogLocationRejection.TIMEOUT -> SetLogLocationFeedback.TIMEOUT
                            SetLogLocationRejection.STALE_FIX -> SetLogLocationFeedback.STALE_FIX
                            SetLogLocationRejection.INACCURATE_FIX -> SetLogLocationFeedback.INACCURATE_FIX
                            else -> SetLogLocationFeedback.UNAVAILABLE
                        }
                        when (acquisition.reason) {
                            SetLogLocationRejection.PROVIDER_DISABLED -> requestLocationSettings()
                            SetLogLocationRejection.PERMISSION_DENIED,
                            SetLogLocationRejection.PERMISSION_REVOKED,
                            SetLogLocationRejection.REQUEST_DENIED,
                            -> requestEntryPermissionStep()
                            else -> Unit
                        }
                    }
                }
            }
        }
        shutterLocationGate.attach(request, cancellation)
    }

    fun prepareCaptured(snapshot: SetLogSnapshot, onReady: () -> Unit) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    derivatives.prepare(
                        source = snapshot.source,
                        tripId = snapshot.tripId,
                        sourceFile = File(snapshot.localAsset.path),
                        coordinates = snapshot.coordinates,
                        takenAt = snapshot.takenAt,
                    )
                }
            }.onSuccess { normalized ->
                if (state.activeToken == snapshot.captureToken && state.stage == SetLogCaptureStage.CAPTURING) {
                    prepared = normalized
                    onReady()
                } else {
                    withContext(Dispatchers.IO) { derivatives.delete(normalized) }
                }
            }.onFailure {
                state = reduceSetLogCapture(
                    state,
                    SetLogCaptureEvent.CallbackFailed(snapshot.captureToken, "DERIVATIVE_PREPARE_FAILED"),
                ).state
            }
        }
    }

    DisposableEffect(context, lifecycleOwner) {
        val camera = SetLogCameraAdapter(context, lifecycleOwner) { callback ->
            when (callback) {
                is SetLogCameraCallback.Ready -> {
                    capabilities = callback.capabilities
                    state = reduceSetLogCapture(state, SetLogCaptureEvent.CameraReady(callback.token)).state
                }
                is SetLogCameraCallback.CapabilitiesChanged -> capabilities = callback.capabilities
                is SetLogCameraCallback.Captured -> {
                    val snapshot = state.pendingSnapshot
                    if (snapshot?.captureToken == callback.token && snapshot.localAsset.outputId == callback.outputId) {
                        prepareCaptured(snapshot) {
                            state = reduceSetLogCapture(
                                state,
                                SetLogCaptureEvent.CaptureCompleted(callback.token, callback.outputId),
                            ).state
                            state.draft?.let(::previewPlace)
                            openConfirmationIfCurrent(callback.token)
                        }
                    }
                }
                else -> callback.toCaptureEvent()?.let { event ->
                    state = reduceSetLogCapture(state, event).state
                }
            }
        }
        adapter = camera
        onDispose {
            shutterLocationGate.cancel()
            adapter = null
            camera.close()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            state = reduceSetLogCapture(state, SetLogCaptureEvent.CameraPermissionGranted).state
            requestEntryPermissionStep()
        } else {
            state = state.copy(stage = SetLogCaptureStage.PERMISSION_REQUIRED, errorCode = "CAMERA_PERMISSION_DENIED")
            locationFeedback = SetLogLocationFeedback.PERMISSION_REQUIRED
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants.values.any { it }
        locationFeedback = when {
            !granted -> SetLogLocationFeedback.PERMISSION_REQUIRED
            !context.isSetLogLocationProviderEnabled() -> SetLogLocationFeedback.PROVIDER_DISABLED
            else -> SetLogLocationFeedback.AVAILABLE
        }
        if (granted) {
            requestEntryPermissionStep()
            state.activeToken
                ?.takeIf {
                    state.stage in setOf(
                        SetLogCaptureStage.CAPTURING,
                        SetLogCaptureStage.DRAFT_READY,
                        SetLogCaptureStage.CONFIRMING,
                    ) &&
                        state.draft?.snapshot?.coordinates == null &&
                        context.hasForegroundLocationPermission() &&
                        context.isSetLogLocationProviderEnabled()
                }
                ?.let(::acquireCameraLocation)
        }
    }
    val locationSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingPermissionStep = null
        locationFeedback = initialLocationFeedback(context)
        state.activeToken
            ?.takeIf {
                state.stage in setOf(
                    SetLogCaptureStage.CAPTURING,
                    SetLogCaptureStage.DRAFT_READY,
                    SetLogCaptureStage.CONFIRMING,
                ) &&
                    state.draft?.snapshot?.coordinates == null &&
                    context.hasForegroundLocationPermission() &&
                    context.isSetLogLocationProviderEnabled()
            }
            ?.let(::acquireCameraLocation)
    }
    requestEntryPermissionStep = {
        val step = setLogEntryPermissionStep(
            cameraPermissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
            locationPermissionGranted = context.hasForegroundLocationPermission(),
            locationProviderEnabled = context.isSetLogLocationProviderEnabled(),
        )
        pendingPermissionStep = step
        locationFeedback = when (step) {
            SetLogEntryPermissionStep.REQUEST_CAMERA,
            SetLogEntryPermissionStep.REQUEST_LOCATION -> SetLogLocationFeedback.PERMISSION_REQUIRED
            SetLogEntryPermissionStep.OPEN_LOCATION_SETTINGS -> SetLogLocationFeedback.PROVIDER_DISABLED
            SetLogEntryPermissionStep.READY -> SetLogLocationFeedback.AVAILABLE
        }
        permissionRequestId++
    }
    requestLocationSettings = {
        pendingPermissionStep = SetLogEntryPermissionStep.OPEN_LOCATION_SETTINGS
        permissionRequestId++
    }
    requestPermissionSettings = {
        context.openStogPermissionSettings()
    }
    LaunchedEffect(pendingPermissionStep, permissionRequestId) {
        when (pendingPermissionStep) {
            SetLogEntryPermissionStep.REQUEST_CAMERA -> {
                when (
                    decidePermissionRequest(
                        granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED,
                        shouldShowRationale = context.shouldShowStogPermissionRationale(
                            Manifest.permission.CAMERA,
                        ),
                        requestAttempted = cameraPermissionRequestAttempted,
                    )
                ) {
                    PermissionRequestDecision.ALREADY_GRANTED -> requestEntryPermissionStep()
                    PermissionRequestDecision.SHOW_RATIONALE -> {
                        permissionPromptTarget = SetLogPermissionPromptTarget.CAMERA
                        permissionPrompt = StogPermissionPrompt(
                            kind = StogPermissionPromptKind.RATIONALE,
                            title = "카메라 권한이 필요해요",
                            detail = "STOG에서 여행 사진을 촬영하려면 카메라 권한이 필요해요.",
                        )
                    }
                    PermissionRequestDecision.OPEN_SETTINGS -> {
                        permissionPromptTarget = SetLogPermissionPromptTarget.CAMERA
                        permissionPrompt = StogPermissionPrompt(
                            kind = StogPermissionPromptKind.SETTINGS,
                            title = "카메라 권한을 켜 주세요",
                            detail = "카메라 권한이 계속 거부되어 앱에서 다시 요청할 수 없어요. Android 앱 설정에서 카메라 권한을 허용해 주세요.",
                        )
                    }
                }
            }
            SetLogEntryPermissionStep.REQUEST_LOCATION -> {
                val locationPermissions = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                when (
                    decidePermissionRequest(
                        granted = context.hasForegroundLocationPermission(),
                        shouldShowRationale = locationPermissions.any {
                            context.shouldShowStogPermissionRationale(it)
                        },
                        requestAttempted = locationPermissionRequestAttempted,
                    )
                ) {
                    PermissionRequestDecision.ALREADY_GRANTED -> requestEntryPermissionStep()
                    PermissionRequestDecision.SHOW_RATIONALE -> {
                        permissionPromptTarget = SetLogPermissionPromptTarget.LOCATION
                        permissionPrompt = StogPermissionPrompt(
                            kind = StogPermissionPromptKind.RATIONALE,
                            title = "위치 권한이 필요해요",
                            detail = "촬영한 사진에 촬영 당시 위치를 안전하게 기록하려면 위치 권한이 필요해요.",
                        )
                    }
                    PermissionRequestDecision.OPEN_SETTINGS -> {
                        permissionPromptTarget = SetLogPermissionPromptTarget.LOCATION
                        permissionPrompt = StogPermissionPrompt(
                            kind = StogPermissionPromptKind.SETTINGS,
                            title = "위치 권한을 켜 주세요",
                            detail = "위치 권한이 계속 거부되어 앱에서 다시 요청할 수 없어요. Android 앱 설정에서 위치 권한을 허용해 주세요.",
                        )
                    }
                }
            }
            SetLogEntryPermissionStep.OPEN_LOCATION_SETTINGS ->
                locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            SetLogEntryPermissionStep.READY, null -> Unit
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val token = state.activeToken
        if (uri == null || token == null || state.stage != SetLogCaptureStage.CAPTURING) {
            if (token != null) state = reduceSetLogCapture(state, SetLogCaptureEvent.CallbackFailed(token, "GALLERY_CANCELLED")).state
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val provenance = derivatives.galleryProvenance(uri, mediaLocationGranted)
                    val source = derivatives.copyGallerySelection(uri)
                    val asset = SetLogLocalAsset(UUID.randomUUID().toString(), UUID.randomUUID().toString(), source.absolutePath)
                    val normalized = derivatives.prepare(
                        PhotoSource.GALLERY,
                        tripId,
                        source,
                        provenance.coordinates,
                        provenance.takenAt?.toString(),
                    )
                    Triple(asset, provenance, normalized)
                }
            }.onSuccess { (asset, provenance, normalized) ->
                if (state.activeToken != token || state.stage != SetLogCaptureStage.CAPTURING) {
                    withContext(Dispatchers.IO) { derivatives.delete(normalized) }
                } else {
                    prepared = normalized
                    state = reduceSetLogCapture(
                        state,
                        SetLogCaptureEvent.GallerySelected(
                            token,
                            asset,
                            provenance,
                            provisionalSetLogCellId(provenance.coordinates),
                        ),
                    ).state
                    state.draft?.let(::previewPlace)
                    openConfirmationIfCurrent(token)
                }
            }.onFailure {
                state = reduceSetLogCapture(state, SetLogCaptureEvent.CallbackFailed(token, "GALLERY_READ_FAILED")).state
            }
        }
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        mediaLocationGranted = granted
        galleryPicker.launch("image/*")
    }
    fun requestGalleryWithOptionalLocation() {
        mediaLocationGranted = context.hasMediaLocationPermission()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaLocationGranted) {
            galleryPicker.launch("image/*")
            return
        }
        when (
            decidePermissionRequest(
                granted = false,
                shouldShowRationale = context.shouldShowStogPermissionRationale(
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                ),
                requestAttempted = mediaLocationPermissionRequestAttempted,
            )
        ) {
            PermissionRequestDecision.ALREADY_GRANTED -> galleryPicker.launch("image/*")
            PermissionRequestDecision.SHOW_RATIONALE -> {
                galleryAfterPermissionPrompt = true
                permissionPromptTarget = SetLogPermissionPromptTarget.MEDIA_LOCATION
                permissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.RATIONALE,
                    title = "사진 위치 정보를 읽을까요?",
                    detail = "갤러리 사진의 EXIF 위치를 보존하려면 미디어 위치 권한이 필요해요. 허용하지 않아도 사진은 위치 없이 저장할 수 있어요.",
                )
            }
            PermissionRequestDecision.OPEN_SETTINGS -> {
                galleryAfterPermissionPrompt = true
                permissionPromptTarget = SetLogPermissionPromptTarget.MEDIA_LOCATION
                permissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.SETTINGS,
                    title = "미디어 위치 권한을 켜 주세요",
                    detail = "사진의 EXIF 위치를 읽으려면 Android 앱 설정에서 미디어 위치 권한을 허용해 주세요. 권한 없이도 사진 선택은 계속할 수 있어요.",
                )
            }
        }
    }

    LaunchedEffect(adapter, state.stage, state.activeToken) {
        val token = state.activeToken ?: return@LaunchedEffect
        if (state.stage == SetLogCaptureStage.INITIALIZING && token != lastBoundToken) {
            lastBoundToken = token
            adapter?.bind(token)
        }
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        state = reduceSetLogCapture(state, SetLogCaptureEvent.Start(granted)).state
        requestEntryPermissionStep()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                mediaLocationGranted = context.hasMediaLocationPermission()
                locationFeedback = initialLocationFeedback(context)
                var permissionFlowResumed = false
                if (permissionPrompt?.kind == StogPermissionPromptKind.SETTINGS) {
                    when (permissionPromptTarget) {
                        SetLogPermissionPromptTarget.CAMERA -> {
                            if (
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionPrompt = null
                                permissionPromptTarget = null
                                requestEntryPermissionStep()
                                permissionFlowResumed = true
                            }
                        }
                        SetLogPermissionPromptTarget.LOCATION -> {
                            if (context.hasForegroundLocationPermission()) {
                                permissionPrompt = null
                                permissionPromptTarget = null
                                requestEntryPermissionStep()
                                permissionFlowResumed = true
                            }
                        }
                        SetLogPermissionPromptTarget.MEDIA_LOCATION -> {
                            if (mediaLocationGranted) {
                                permissionPrompt = null
                                permissionPromptTarget = null
                                galleryAfterPermissionPrompt = false
                                galleryPicker.launch("image/*")
                                permissionFlowResumed = true
                            }
                        }
                        null -> Unit
                    }
                }
                if (!permissionFlowResumed && pendingPermissionStep != null) {
                    requestEntryPermissionStep()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun close() {
        shutterLocationGate.cancel()
        val previous = state
        val reduction = reduceSetLogCapture(previous, SetLogCaptureEvent.ClosePressed)
        val uncommitted = preparedPhotoForCleanup(previous, reduction, prepared)
        state = reduction.state
        uncommitted?.let { scope.launch(Dispatchers.IO) { derivatives.delete(it) } }
        onBack()
    }

    fun retake() {
        shutterLocationGate.cancel()
        val previous = state
        val reduction = reduceSetLogCapture(previous, SetLogCaptureEvent.RetakePressed)
        val old = preparedPhotoForCleanup(previous, reduction, prepared)
        state = reduction.state
        old?.let {
            prepared = null
            scope.launch(Dispatchers.IO) { derivatives.delete(it) }
        }
    }

    fun downloadSaved() {
        val photoId = state.savedPhotoId ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val remote = api.detail(accessToken, photoId)
                    val url = remote.originalUrl ?: throw IOException("PHOTO_DOWNLOAD_URL_MISSING")
                    PhotoDownloadStore(context).download(url, "stog-$photoId")
                }
            }
        }
    }

    fun record() {
        val reduction = reduceSetLogCapture(state, SetLogCaptureEvent.RecordPressed)
        if (reduction.effects.none { it is SetLogCaptureEffect.CommitRecord }) return
        state = reduction.state
        val draft = reduction.effects.filterIsInstance<SetLogCaptureEffect.CommitRecord>().single().draft
        val normalized = prepared ?: run {
            state = state.copy(stage = SetLogCaptureStage.FAILED, errorCode = "DERIVATIVE_MISSING")
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    queue.enqueue(pendingSetLogEntity(draft, normalized, clock.instant().toEpochMilli()))
                }
            }.onSuccess { result ->
                state = reduceSetLogCapture(
                    state,
                    setLogPersistenceEvent(draft.snapshot.captureToken, result),
                ).state
            }.onFailure {
                state = reduceSetLogCapture(
                    state,
                    SetLogCaptureEvent.DeliveryFailed(
                        draft.snapshot.captureToken,
                        "SET_LOG_PERSIST_FAILED",
                        retryable = false,
                    ),
                ).state
            }
        }
    }

    val draft = state.draft
    LaunchedEffect(draft?.snapshot?.localAsset?.assetId, state.stage) {
        val clientUploadId = prepared?.id ?: return@LaunchedEffect
        if (state.stage != SetLogCaptureStage.PENDING_SYNC) return@LaunchedEffect
        StogDatabase.get(context).pendingSetLogDao().observe(clientUploadId).collectLatest { row ->
            val currentDraft = state.draft ?: return@collectLatest
            when (row?.outboxState) {
                SetLogOutboxState.ACKNOWLEDGED -> state = reduceSetLogCapture(
                    state,
                    SetLogCaptureEvent.DeliverySucceeded(
                        currentDraft.snapshot.captureToken,
                        requireNotNull(row.remotePhotoId),
                        publicationState(row.publicationStatus),
                    ),
                ).state
                SetLogOutboxState.TERMINAL -> state = reduceSetLogCapture(
                    state,
                    SetLogCaptureEvent.DeliveryFailed(
                        currentDraft.snapshot.captureToken,
                        row.errorCode ?: "SET_LOG_DELIVERY_FAILED",
                        retryable = false,
                    ),
                ).state
                else -> Unit
            }
        }
    }

    permissionPrompt?.let { prompt ->
        StogPermissionDialog(
            prompt = prompt,
            onDismiss = {
                permissionPrompt = null
                permissionPromptTarget = null
                if (galleryAfterPermissionPrompt) {
                    galleryAfterPermissionPrompt = false
                    galleryPicker.launch("image/*")
                }
            },
            onPrimary = {
                val target = permissionPromptTarget
                permissionPrompt = null
                permissionPromptTarget = null
                if (prompt.kind == StogPermissionPromptKind.SETTINGS) {
                    context.openStogPermissionSettings()
                    return@StogPermissionDialog
                }
                when (target) {
                    SetLogPermissionPromptTarget.CAMERA -> {
                        cameraPermissionRequestAttempted = true
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    SetLogPermissionPromptTarget.LOCATION -> {
                        locationPermissionRequestAttempted = true
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                    SetLogPermissionPromptTarget.MEDIA_LOCATION -> {
                        galleryAfterPermissionPrompt = false
                        mediaLocationPermissionRequestAttempted = true
                        mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                    null -> Unit
                }
            },
        )
    }

    BackHandler(onBack = ::close)
    if (draft != null && state.stage in confirmationStages) {
        SetLogConfirmationSurface(
            draft = draft,
            previewPath = prepared?.thumbnailPath,
            recordTitle = tripTitle,
            stage = state.stage,
            onCaptionChanged = { state = reduceSetLogCapture(state, SetLogCaptureEvent.CaptionChanged(it)).state },
            onVisibilityChanged = { state = reduceSetLogCapture(state, SetLogCaptureEvent.VisibilityChanged(it)).state },
            onRetake = ::retake,
            onRecord = ::record,
            onClose = ::close,
            onDownload = ::downloadSaved,
            hasDurablePendingRecord = state.hasDurablePendingRecord,
            modifier = modifier,
        )
    } else {
        SetLogCaptureSurface(
            previewView = adapter?.previewView,
            stage = state.stage,
            capabilities = capabilities,
            locationFeedback = locationFeedback,
            placeLabel = null,
            onClose = ::close,
            onFlash = { state.activeToken?.let { adapter?.cycleFlash(it) } },
            onGallery = {
                val reduction = reduceSetLogCapture(state, SetLogCaptureEvent.GalleryPressed)
                if (reduction.effects.any { it is SetLogCaptureEffect.OpenGallery }) {
                    state = reduction.state
                    requestGalleryWithOptionalLocation()
                }
            },
            onLocationSettings = requestLocationSettings,
            onPermissionSettings = requestPermissionSettings,
            onShutter = {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                        requestEntryPermissionStep()
                    state.stage == SetLogCaptureStage.ERROR -> {
                        val previousToken = state.activeToken
                        val rebound = reduceSetLogCapture(state, SetLogCaptureEvent.RetakePressed)
                        state = rebound.state
                        val newToken = rebound.state.activeToken
                        if (newToken != null && newToken != previousToken) {
                            lastBoundToken = newToken
                            adapter?.bind(newToken)
                        }
                    }
                    state.stage == SetLogCaptureStage.READY -> {
                        val token = state.activeToken ?: return@SetLogCaptureSurface
                        val source = derivatives.createCameraOutput()
                        val asset = SetLogLocalAsset(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            source.absolutePath,
                        )
                        val reduction = reduceSetLogCapture(
                            state,
                            SetLogCaptureEvent.ShutterPressed(
                                takenAt = Instant.now(clock),
                                location = null,
                                provisionalCellId = null,
                                output = asset,
                            ),
                        )
                        state = reduction.state
                        val effect = reduction.effects
                            .filterIsInstance<SetLogCaptureEffect.TakePhoto>()
                            .singleOrNull()
                        if (effect == null) {
                            source.delete()
                            return@SetLogCaptureSurface
                        }
                        val dispatched = adapter?.capture(effect.token, effect.output) == true
                        setLogCameraLog("camera capture dispatched=$dispatched")
                        if (!dispatched) {
                            source.delete()
                            state = reduceSetLogCapture(
                                state,
                                SetLogCaptureEvent.CallbackFailed(
                                    effect.token,
                                    "CAPTURE_NOT_READY",
                                ),
                            ).state
        } else if (
                            context.hasForegroundLocationPermission() &&
                            context.isSetLogLocationProviderEnabled()
                        ) {
                            acquireCameraLocation(token)
                        } else {
                            requestEntryPermissionStep()
                        }
                    }
                }
            },
            onSwitchLens = {
                shutterLocationGate.cancel()
                val reduction = reduceSetLogCapture(state, SetLogCaptureEvent.RebindRequested)
                val newToken = reduction.state.activeToken
                if (newToken != null && newToken != state.activeToken) {
                    state = reduction.state
                    lastBoundToken = newToken
                    adapter?.switchLens(newToken)
                }
            },
            modifier = modifier,
        )
    }
}

internal fun setLogPersistenceEvent(
    token: SetLogCaptureToken,
    result: SetLogEnqueueResult,
) = SetLogCaptureEvent.RecordPersisted(
    token = token,
    schedulingDeferred = result is SetLogEnqueueResult.SchedulingDeferred,
)

internal fun preparedPhotoForCleanup(
    previous: SetLogCaptureState,
    reduction: SetLogCaptureReduction,
    prepared: PreparedPhoto?,
): PreparedPhoto? = prepared?.takeIf {
    reduction.state != previous && reduction.effects.any { effect -> effect is SetLogCaptureEffect.CleanupAsset }
}

internal class SetLogShutterLocationGate {
    internal class Request internal constructor(val token: SetLogCaptureToken) {
        internal var cancellation: SetLogLocationCancellation? = null
        internal var cancelled = false
    }

    private var active: Request? = null

    fun begin(token: SetLogCaptureToken): Request? {
        if (active != null) return null
        return Request(token).also { active = it }
    }

    fun attach(request: Request, cancellation: SetLogLocationCancellation) {
        when {
            active === request -> request.cancellation = cancellation
            request.cancelled -> cancellation.cancel()
        }
    }

    fun deliver(
        request: Request,
        activeToken: SetLogCaptureToken?,
        stage: SetLogCaptureStage,
    ): Boolean {
        if (active !== request) return false
        active = null
        return activeToken == request.token && stage in setOf(
            SetLogCaptureStage.READY,
            SetLogCaptureStage.CAPTURING,
            SetLogCaptureStage.DRAFT_READY,
            SetLogCaptureStage.CONFIRMING,
        )
    }

    fun cancel() {
        val request = active ?: return
        active = null
        request.cancelled = true
        request.cancellation?.cancel()
    }
}

internal fun pendingSetLogEntity(
    draft: SetLogDraft,
    photo: PreparedPhoto,
    createdAt: Long,
): PendingSetLogEntity {
    val snapshot = draft.snapshot
    require(photo.tripId == snapshot.tripId && photo.source == snapshot.source)
    val matched = draft.placePreview as? SetLogPlacePreview.Matched
    return PendingSetLogEntity(
        clientUploadId = photo.id,
        accountId = snapshot.accountId,
        userId = snapshot.userId.toString(),
        tripId = snapshot.tripId,
        source = snapshot.source.name.lowercase(),
        originalPath = photo.normalizedOriginalPath,
        thumbnailPath = photo.thumbnailPath,
        sourcePath = photo.sourceFilePath,
        originalSize = photo.normalizedOriginalBytes,
        thumbnailSize = photo.thumbnailBytes,
        originalSha256 = photo.originalSha256,
        thumbnailSha256 = photo.thumbnailSha256,
        latitude = snapshot.coordinates?.latitude,
        longitude = snapshot.coordinates?.longitude,
        accuracyMeters = snapshot.accuracyMeters,
        locationProvenance = when (snapshot.locationProvenance) {
            SetLogLocationProvenance.CAMERA_FOREGROUND -> "camera_foreground"
            SetLogLocationProvenance.GALLERY_EXIF -> "gallery_exif"
            SetLogLocationProvenance.MISSING -> null
        },
        takenAt = snapshot.takenAt,
        caption = draft.captionForRecord,
        placeResolutionStatus = when (draft.placePreview) {
            is SetLogPlacePreview.Matched -> "matched"
            SetLogPlacePreview.NoMatch -> "no_match"
            SetLogPlacePreview.Pending, is SetLogPlacePreview.Failed -> "pending"
        },
        expectedPlaceId = matched?.placeId,
        visibility = draft.visibilityIntent.name.lowercase(),
        publicConsent = draft.visibilityIntent == SetLogVisibilityIntent.PUBLIC,
        createdAt = createdAt,
    )
}

private val confirmationStages = setOf(
    SetLogCaptureStage.CONFIRMING,
    SetLogCaptureStage.SAVING,
    SetLogCaptureStage.PENDING_SYNC,
    SetLogCaptureStage.SAVED,
    SetLogCaptureStage.FAILED,
)

private fun publicationState(value: String?): SetLogPublicationState = when (value) {
    "private" -> SetLogPublicationState.PRIVATE
    "trip_not_public" -> SetLogPublicationState.TRIP_NOT_PUBLIC
    "moderation_pending" -> SetLogPublicationState.MODERATION_PENDING
    "public" -> SetLogPublicationState.PUBLIC
    else -> SetLogPublicationState.NOT_RECORDED
}

private fun initialLocationFeedback(context: Context): SetLogLocationFeedback = when {
    !context.hasForegroundLocationPermission() -> SetLogLocationFeedback.PERMISSION_REQUIRED
    !context.isSetLogLocationProviderEnabled() -> SetLogLocationFeedback.PROVIDER_DISABLED
    else -> SetLogLocationFeedback.AVAILABLE
}

private fun Context.hasForegroundLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.hasMediaLocationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun PhotoRecordScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(StogCanvas).statusBarsPadding()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .stogTouchTarget()
                .testTag("photo_record_back_action"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = "뒤로",
                tint = StogInk,
            )
        }
        Text(
            title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            color = StogInk,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("photo_record_content"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun UploadStatusCard(
    state: PhotoUploadUiState,
    onUpload: (PreparedPhoto) -> Unit,
    onRetry: () -> Unit,
    onDownload: (RemotePhoto) -> Unit = {},
) {
    when (state) {
        PhotoUploadUiState.Idle -> Unit
        PhotoUploadUiState.Preparing -> StogStatePanel(StogSurfaceState.LOADING, "사진을 준비하는 중", "저장본과 미리보기를 안전하게 만들고 있어요.")
        is PhotoUploadUiState.Ready -> RecordSurface {
            Text("사진을 올릴 준비가 됐어요.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { onUpload(state.photo) }, modifier = Modifier.fillMaxWidth().stogTouchTarget(), colors = recordPrimaryButtonColors()) { Text("STOG에 저장") }
        }
        is PhotoUploadUiState.Uploading -> StogStatePanel(StogSurfaceState.LOADING, "${uploadStageLabel(state.stage)} 중", "화면을 떠나지 않고 완료 여부를 확인해 주세요.")
        is PhotoUploadUiState.RetryableFailure -> StogStatePanel(StogSurfaceState.OFFLINE, "사진 전송을 완료하지 못했어요", state.message, actionLabel = "새 주소로 다시 올리기", onAction = onRetry)
        is PhotoUploadUiState.TerminalFailure -> StogStatePanel(StogSurfaceState.ERROR, "사진 요청을 다시 확인해 주세요", state.code, action = StogSurfaceAction.NONE)
        is PhotoUploadUiState.AuthenticationRequired -> StogStatePanel(StogSurfaceState.AUTH_EXPIRED, "로그인이 만료되었어요", "다시 로그인하면 준비한 사진을 이어서 올릴 수 있어요.", action = StogSurfaceAction.NONE)
        is PhotoUploadUiState.Completed -> RecordSurface {
            Text("사진을 저장했어요", style = MaterialTheme.typography.titleMedium)
            Text("나의 여행에서 공개 범위와 공개 동의를 따로 관리할 수 있어요.", color = StogMuted)
            Button(
                onClick = { onDownload(state.photo) },
                modifier = Modifier.fillMaxWidth().stogTouchTarget().testTag("capture_download_action"),
                colors = recordPrimaryButtonColors(),
            ) { Text("기기에 다운로드") }
        }
        is PhotoUploadUiState.FinalizationUnknown -> StogStatePanel(StogSurfaceState.ERROR, "사진 저장 확인이 필요해요", "전송한 두 파일은 유지되며 같은 정보로 안전하게 다시 확인해요.", actionLabel = "저장 확인 다시 시도", onAction = onRetry)
    }
}

@Composable
internal fun RecordSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = StogSurface),
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun recordPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = StogYellow,
    contentColor = StogInk,
    disabledContainerColor = StogBorder,
    disabledContentColor = StogMuted,
)

private fun uploadStageLabel(stage: UploadStage): String = when (stage) {
    UploadStage.REQUESTING_URLS -> "안전한 업로드 주소를 준비"
    UploadStage.ORIGINAL -> "저장본을 직접 업로드"
    UploadStage.THUMBNAIL -> "미리보기를 직접 업로드"
    UploadStage.FINALIZING -> "사진 정보를 저장"
}
