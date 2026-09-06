package com.stog.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.user.UserApiClient
import com.stog.app.feature.auth.AuthClient
import com.stog.app.feature.auth.AuthRequestException
import com.stog.app.feature.auth.AuthSessionRuntime
import com.stog.app.feature.auth.AuthSessionRetrier
import com.stog.app.feature.auth.AuthTokenStore
import com.stog.app.feature.auth.LoginScreen
import com.stog.app.feature.auth.LoginProvider
import com.stog.app.feature.auth.StogAuthSession
import com.stog.app.core.database.PendingSetLogWorkInitializer
import com.stog.app.core.database.PendingShareWorkInitializer
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.home.HomeDashboardScreen
import com.stog.app.feature.plan.share_import.ShareImportCoordinator
import com.stog.app.feature.plan.share_import.ShareImportScreen
import com.stog.app.feature.plan.share_import.ShareImportUiState
import com.stog.app.feature.plan.share_import.ShareImportInbox
import com.stog.app.feature.plan.share_import.ShareMentionDecision
import com.stog.app.feature.plan.share_import.StoredShareImport
import com.stog.app.feature.plan.share_import.shareImportReviewId
import com.stog.app.feature.plan.trip.TripExperienceScreen
import com.stog.app.feature.plan.trip.NewTripScreen
import com.stog.app.feature.profile.InitialProfileState
import com.stog.app.feature.profile.InitialSurveyErrorScreen
import com.stog.app.feature.profile.InitialSurveyLoadingScreen
import com.stog.app.feature.profile.PreferenceAnalysisLoadingScreen
import com.stog.app.feature.profile.PreferenceSurveyScreen
import com.stog.app.feature.profile.PreferenceSurveyResultScreen
import com.stog.app.feature.profile.ProfileApiClient
import com.stog.app.feature.profile.ProfileSurveyResult
import com.stog.app.feature.profile.UserProfileScreen
import com.stog.app.feature.profile.needsInitialSurvey
import com.stog.app.feature.record.PhotoArchiveScreen
import com.stog.app.feature.record.PhotoCaptureScreen
import com.stog.app.feature.record.PostVisitReview
import com.stog.app.feature.record.FailureReason
import com.stog.app.feature.record.RecordingActivationCoordinator
import com.stog.app.feature.record.RecordingNotifications
import com.stog.app.feature.record.TripLocationService
import com.stog.app.feature.social.DiscoverScreen
import com.stog.app.feature.space.MapMenu
import com.stog.app.feature.space.MapShellState
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.feature.space.PlaceDetailsState
import com.stog.app.feature.space.PlaceSearchScreen
import com.stog.app.feature.space.MapScreenState
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.SpaceMapScreen
import com.stog.app.feature.space.SheetLevel
import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.MapCellTarget
import com.stog.app.feature.space.mapScreenStateForCell
import com.stog.app.feature.space.mapScreenStateForPlace
import com.stog.app.feature.space.decodePlaceSearchCandidates
import com.stog.app.feature.space.encodePlaceSearchCandidates
import com.stog.app.feature.space.TripInviteEntry
import com.stog.app.feature.space.shouldResumeBookmark
import com.stog.app.feature.space.tripInviteEntryFor
import com.stog.app.feature.space.tripInviteTokenFromUri
import com.stog.app.feature.space.toPlaceDetails
import com.stog.app.navigation.AppDestination
import com.stog.app.navigation.HOME_PAGE_TARGET
import com.stog.app.navigation.STOBEE_TARGET
import com.stog.app.navigation.backDestination
import com.stog.app.navigation.destinationFromSavedRoute
import com.stog.app.ui.theme.STOGTheme
import com.stog.app.ui.PermissionRequestDecision
import com.stog.app.ui.StogPermissionDialog
import com.stog.app.ui.StogPermissionPrompt
import com.stog.app.ui.StogPermissionPromptKind
import com.stog.app.ui.decidePermissionRequest
import com.stog.app.ui.openStogPermissionSettings
import com.stog.app.ui.shouldShowStogPermissionRationale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

internal const val HOME_STARTS_WITH_MAP = false

class MainActivity : ComponentActivity() {
    private val authClient by lazy { AuthClient(BuildConfig.STOG_API_BASE_URL) }
    private val profileClient by lazy { ProfileApiClient(BuildConfig.STOG_API_BASE_URL) }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val authTokenStore by lazy { AuthTokenStore(this) }
    private val authSessionRetrier by lazy {
        AuthSessionRetrier(
            loadTokens = authTokenStore::load,
            refresh = authClient::refresh,
            saveSession = authTokenStore::save,
            onSessionRefreshed = { session ->
                runOnUiThread {
                    if (authSession?.userId == session.userId) {
                        authSession = session
                    }
                }
            },
            onSessionExpired = {
                runOnUiThread {
                    expireAuthentication("로그인 상태가 만료되었어요. 다시 로그인해주세요.")
                }
            },
        )
    }
    private var destination by mutableStateOf(AppDestination.LOGIN)
    private lateinit var shareImportCoordinator: ShareImportCoordinator
    private var shareImportState by mutableStateOf<ShareImportUiState>(
        ShareImportUiState.Idle,
    )
    private var storedImports by mutableStateOf<List<StoredShareImport>>(emptyList())
    private var showingMap by mutableStateOf(HOME_STARTS_WITH_MAP)
    private var loginMessage by mutableStateOf<String?>(null)
    private var isLoggingIn by mutableStateOf(false)
    private var loginProvider by mutableStateOf<LoginProvider?>(null)
    private var loginErrorProvider by mutableStateOf<LoginProvider?>(null)
    private var authSession by mutableStateOf<StogAuthSession?>(null)
    private var initialProfileState by mutableStateOf(InitialProfileState.IDLE)
    private var initialProfileError by mutableStateOf<String?>(null)
    private var surveySubmissionError by mutableStateOf<String?>(null)
    private var surveySubmitting by mutableStateOf(false)
    private var surveyResult by mutableStateOf<ProfileSurveyResult?>(null)
    private var pendingBookmarkCandidate by mutableStateOf<PlaceSearchCandidate?>(null)
    private var pendingTripInviteToken by mutableStateOf<String?>(null)
    private var joiningTripInvite by mutableStateOf(false)
    private var searchStartedFromMap by mutableStateOf(false)
    private var searchCandidates by mutableStateOf<List<PlaceSearchCandidate>>(emptyList())
    private var mapScreenState by mutableStateOf(MapScreenState())
    private var pendingTripDetail by mutableStateOf<TripSummary?>(null)
    private var tripListRefreshKey by mutableStateOf(0)
    private var aiTripId by mutableStateOf<Long?>(null)
    private var aiTripTitle by mutableStateOf<String?>(null)
    private var homeLocationRefreshKey by mutableStateOf(0)
    private var pendingShareImportNotificationId: String? = null
    private var pendingPermissionPurpose = PermissionPurpose.SHARE_NOTIFICATION
    private var permissionPrompt by mutableStateOf<StogPermissionPrompt?>(null)
    private var permissionPromptPurpose by mutableStateOf<PermissionPurpose?>(null)
    private val permissionRequestAttempts = mutableSetOf<PermissionPurpose>()
    private var permissionRequestInFlight = false
    private var permissionFlowActive = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionRequestInFlight = false
        val granted = when (pendingPermissionPurpose) {
            PermissionPurpose.SHARE_NOTIFICATION -> grants[PERMISSION_POST_NOTIFICATIONS] == true
            PermissionPurpose.RECORDING_LOCATION ->
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            PermissionPurpose.RECORDING_BACKGROUND_LOCATION -> grants[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
            PermissionPurpose.HOME_LOCATION -> true
        }
        when (pendingPermissionPurpose) {
            PermissionPurpose.SHARE_NOTIFICATION -> if (granted) {
                permissionFlowActive = false
                pendingShareImportNotificationId?.let(shareImportCoordinator::postNotification)
            } else permissionFlowActive = false
            PermissionPurpose.RECORDING_LOCATION -> if (granted) {
                pendingPermissionPurpose = PermissionPurpose.SHARE_NOTIFICATION
                homeLocationRefreshKey++
                requestRecordingBackgroundLocationOrActivate()
            } else {
                permissionFlowActive = true
                RecordingNotifications(this).actionRequired(FailureReason.PERMISSION)
                requestRecordingLocationPermission()
            }
            PermissionPurpose.RECORDING_BACKGROUND_LOCATION -> if (granted) {
                pendingPermissionPurpose = PermissionPurpose.SHARE_NOTIFICATION
                activateCalendarAndRequestNotification()
            } else {
                permissionFlowActive = true
                RecordingNotifications(this).actionRequired(FailureReason.PERMISSION)
                requestRecordingBackgroundLocationOrActivate()
            }
            PermissionPurpose.HOME_LOCATION -> {
                permissionFlowActive = false
                homeLocationRefreshKey++
            }
        }
        pendingShareImportNotificationId = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        destination = destinationFromSavedRoute(
            savedInstanceState?.getString(STATE_DESTINATION),
        ) ?: AppDestination.LOGIN
        if (authTokenStore.load() != null) {
            initialProfileState = InitialProfileState.CHECKING
        }
        pendingBookmarkCandidate = savedInstanceState?.placeSearchCandidate()
        pendingTripInviteToken = savedInstanceState?.getString(STATE_TRIP_INVITE_TOKEN)
        searchCandidates = savedInstanceState
            ?.getStringArrayList(STATE_SEARCH_CANDIDATES)
            ?.let(::decodePlaceSearchCandidates)
            .orEmpty()
        showingMap = savedInstanceState?.getBoolean(STATE_SHOWING_MAP) ?: HOME_STARTS_WITH_MAP
        enableEdgeToEdge()
        shareImportCoordinator = ShareImportCoordinator(this)
        storedImports = shareImportCoordinator.loadAll()
        val incomingTripInviteToken = tripInviteTokenFromUri(intent.data)
        val incomingNaverTicket = naverLoginTicketFromUri(intent.data)
        when {
            incomingNaverTicket != null -> receiveNaverLoginTicket(incomingNaverTicket)
            incomingTripInviteToken != null -> receiveTripInvite(incomingTripInviteToken)
            intent.isShareIntent() -> receiveShare(intent)
            intent.shareImportReviewId() != null -> openShareImportReview(intent.shareImportReviewId()!!)
            savedInstanceState?.getString(STATE_SHARE_IMPORT_ID) != null -> {
                openShareImportReview(savedInstanceState.getString(STATE_SHARE_IMPORT_ID)!!)
            }
        }

        setContent {
            val openSearch = {
                searchStartedFromMap = false
                searchCandidates = emptyList()
                mapScreenState = MapScreenState()
                showingMap = false
                destination = AppDestination.PLACE_SEARCH
            }
            val openDiscoveryCell = { target: MapCellTarget ->
                searchCandidates = emptyList()
                mapScreenState = mapScreenStateForCell(target, returnToDiscoverOnClose = true)
                showingMap = true
                destination = AppDestination.HOME
            }
            val openStobee = {
                searchStartedFromMap = false
                searchCandidates = emptyList()
                mapScreenState = MapScreenState(
                    shellState = MapShellState().openAiGuide(),
                    sheetLevel = SheetLevel.HalfExpanded,
                )
                showingMap = STOBEE_TARGET.showingMap
                destination = STOBEE_TARGET.destination
            }
            val pendingReview by remember(authSession?.userId) {
                authSession?.userId?.toString()?.let { accountId ->
                    StogDatabase.get(this).visitOutboxDao().observePendingReview(accountId)
                } ?: flowOf(null)
            }.collectAsState(initial = null)
            val systemBars = appSystemBarAppearance(destination, showingMap)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = systemBars.lightStatusBars
                    isAppearanceLightNavigationBars = systemBars.lightNavigationBars
                }
            }
            STOGTheme {
                val parentDestination = destination.backDestination()
                val canNavigateBack = initialProfileState == InitialProfileState.IDLE ||
                    initialProfileState == InitialProfileState.COMPLETE
                BackHandler(enabled = parentDestination != null && canNavigateBack) {
                    if (destination == AppDestination.PLACE_SEARCH) {
                        searchStartedFromMap = false
                        searchCandidates = emptyList()
                    }
                    destination = checkNotNull(parentDestination)
                }
                when (initialProfileState) {
                    InitialProfileState.CHECKING -> InitialSurveyLoadingScreen(
                        title = "STOG를 준비하고 있어요",
                        modifier = Modifier.fillMaxSize(),
                    )
                    InitialProfileState.ERROR -> InitialSurveyErrorScreen(
                        onRetry = {
                            authSession?.let(::checkInitialProfile)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    InitialProfileState.REQUIRED -> if (surveySubmitting) {
                        PreferenceAnalysisLoadingScreen(
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PreferenceSurveyScreen(
                            onSubmit = ::submitInitialSurvey,
                            submitting = false,
                            errorMessage = surveySubmissionError,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    InitialProfileState.RESULT -> surveyResult?.let { result ->
                        PreferenceSurveyResultScreen(
                            result = result,
                            onContinue = {
                                authSession?.let(::continueAfterInitialProfile)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: InitialSurveyLoadingScreen(
                        title = "성향 분석 결과를 불러오고 있어요",
                        modifier = Modifier.fillMaxSize(),
                    )
                    InitialProfileState.IDLE,
                    InitialProfileState.COMPLETE,
                    -> when (destination) {
                    AppDestination.LOGIN -> LoginScreen(
                        message = loginMessage,
                        isLoading = isLoggingIn,
                        loadingProvider = loginProvider,
                        errorProvider = loginErrorProvider,
                        onKakaoLogin = ::startKakaoLogin,
                        onGoogleLogin = {
                            startGoogleLogin()
                        },
                        onNaverLogin = ::startNaverLogin,
                        onGuest = {
                            val returnsToSearch = pendingBookmarkCandidate != null
                            pendingBookmarkCandidate = null
                            destination = if (returnsToSearch) {
                                AppDestination.PLACE_SEARCH
                            } else {
                                AppDestination.HOME
                            }
                        },
                    )
                    AppDestination.CAPTURE -> PhotoCaptureScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        userId = authSession?.userId,
                        onBack = { destination = AppDestination.HOME },
                        onAuthenticationRequired = ::photoAuthenticationRequired,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.ARCHIVE -> PhotoArchiveScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        viewerId = authSession?.userId,
                        onBack = { destination = AppDestination.HOME },
                        onAuthenticationRequired = ::photoAuthenticationRequired,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.DISCOVER -> DiscoverScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        onLoginRequired = {
                            showLoginMessage("로그인 후 공개 여행 기록에 좋아요를 남길 수 있어요.")
                            destination = AppDestination.LOGIN
                        },
                        onSearch = openSearch,
                        onMenuSelected = { menu ->
                            when (menu) {
                                MapMenu.SOCIAL -> Unit
                                MapMenu.TRAVEL -> destination = AppDestination.TRAVEL
                                MapMenu.RECOMMENDATIONS -> destination = AppDestination.USER
                                else -> {
                                    mapScreenState = MapScreenState(
                                        shellState = MapShellState(selectedMenu = menu),
                                    )
                                    showingMap = false
                                    destination = AppDestination.HOME
                                }
                            }
                        },
                        onOpenMap = openDiscoveryCell,
                        onOpenStobee = {
                            openStobee()
                        },
                        viewerNickname = authSession?.nickname,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.USER -> UserProfileScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        nickname = authSession?.nickname,
                        authenticated = !authSession?.accessToken.isNullOrBlank(),
                        onLogout = ::logout,
                        onLogin = {
                            showLoginMessage("로그인하면 여행 기록과 성향을 확인할 수 있어요.")
                            destination = AppDestination.LOGIN
                        },
                        onSearch = openSearch,
                        onMenuSelected = { menu ->
                            when (menu) {
                                MapMenu.RECOMMENDATIONS -> Unit
                                MapMenu.SOCIAL -> destination = AppDestination.DISCOVER
                                MapMenu.TRAVEL -> destination = AppDestination.TRAVEL
                                MapMenu.HOME -> {
                                    searchStartedFromMap = false
                                    searchCandidates = emptyList()
                                    mapScreenState = MapScreenState(
                                        shellState = MapShellState(selectedMenu = MapMenu.HOME),
                                    )
                                    showingMap = false
                                    destination = AppDestination.HOME
                                }
                            }
                        },
                        onOpenStobee = {
                            openStobee()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.SURVEY -> PreferenceSurveyScreen(
                        onSubmit = ::submitInitialSurvey,
                        submitting = surveySubmitting,
                        errorMessage = surveySubmissionError,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.SURVEY_RESULT -> surveyResult?.let { result ->
                        PreferenceSurveyResultScreen(
                            result = result,
                            onContinue = {
                                authSession?.let(::continueAfterInitialProfile)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: InitialSurveyLoadingScreen(
                        title = "성향 분석 결과를 불러오고 있어요",
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.NEW_TRIP -> NewTripScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        onBack = {
                            pendingTripDetail = null
                            destination = if (pendingBookmarkCandidate == null) {
                                AppDestination.TRAVEL
                            } else {
                                AppDestination.PLACE_SEARCH
                            }
                        },
                        onLoginRequired = {
                            showLoginMessage("로그인 후 새 여행을 만들 수 있어요.")
                            destination = AppDestination.LOGIN
                        },
                        onCreated = {
                            tripListRefreshKey += 1
                            pendingTripDetail = null
                            destination = if (pendingBookmarkCandidate == null) {
                                AppDestination.TRAVEL
                            } else {
                                AppDestination.PLACE_SEARCH
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.TRAVEL -> TripExperienceScreen(
                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                        accessToken = authSession?.accessToken,
                        confirmedShareImports = storedImports,
                        initialTrip = pendingTripDetail,
                        onInitialTripCleared = { pendingTripDetail = null },
                        onLoginRequired = {
                            showLoginMessage("로그인 후 여행을 관리할 수 있어요.")
                            destination = AppDestination.LOGIN
                        },
                        onCapture = { destination = AppDestination.CAPTURE },
                        onArchive = { destination = AppDestination.ARCHIVE },
                        onSearch = openSearch,
                        onMenuSelected = { menu ->
                            when (menu) {
                                MapMenu.TRAVEL -> Unit
                                MapMenu.SOCIAL -> destination = AppDestination.DISCOVER
                                MapMenu.RECOMMENDATIONS -> destination = AppDestination.USER
                                else -> {
                                    mapScreenState = MapScreenState(
                                        shellState = MapShellState(selectedMenu = menu),
                                    )
                                    showingMap = false
                                    destination = AppDestination.HOME
                                }
                            }
                        },
                        onOpenStobee = {
                            aiTripId = null
                            aiTripTitle = null
                            openStobee()
                        },
                        onCreateTrip = {
                            pendingTripDetail = null
                            destination = AppDestination.NEW_TRIP
                        },
                        tripListRefreshKey = tripListRefreshKey,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppDestination.HOME,
                    AppDestination.PLACE_SEARCH,
                    -> if (destination == AppDestination.HOME) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (showingMap) {
                                    SpaceMapScreen(
                                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                                        accessToken = authSession?.accessToken,
                                        nickname = authSession?.nickname,
                                        confirmedShareImports = storedImports,
                                        searchCandidates = searchCandidates,
                                        searchOverlayOpen = searchStartedFromMap,
                                        initialState = mapScreenState,
                                        locationRefreshKey = homeLocationRefreshKey,
                                        onPlaceSearch = { currentMapState ->
                                            searchStartedFromMap = true
                                            searchCandidates = emptyList()
                                            mapScreenState = currentMapState
                                            destination = AppDestination.PLACE_SEARCH
                                        },
                                        onSavePlace = { candidate ->
                                            searchStartedFromMap = true
                                            searchCandidates = emptyList()
                                            pendingBookmarkCandidate = candidate
                                            if (authSession == null) {
                                                showLoginMessage("로그인 후 장소를 여행에 담을 수 있어요.")
                                                destination = AppDestination.LOGIN
                                            } else {
                                                destination = AppDestination.PLACE_SEARCH
                                            }
                                        },
                                        onLoginRequired = {
                            showLoginMessage("로그인 후 여행을 관리할 수 있어요.")
                                            destination = AppDestination.LOGIN
                                        },
                                        onCapture = { destination = AppDestination.CAPTURE },
                                        onArchive = { destination = AppDestination.ARCHIVE },
                                        onOpenHome = {
                                            searchStartedFromMap = false
                                            searchCandidates = emptyList()
                                            mapScreenState = MapScreenState()
                                            showingMap = HOME_PAGE_TARGET.showingMap
                                            destination = HOME_PAGE_TARGET.destination
                                        },
                                        onOpenTravel = { destination = AppDestination.TRAVEL },
                                        onOpenDiscover = {
                                            destination = AppDestination.DISCOVER
                                        },
                                        onOpenUser = {
                                            destination = AppDestination.USER
                                        },
                                        aiTripId = aiTripId,
                                        aiTripTitle = aiTripTitle,
                                        onSelectStobeeTrip = { tripId, tripTitle ->
                                            aiTripId = tripId.takeIf { it > 0 }
                                            aiTripTitle = tripTitle
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (shareImportState is ShareImportUiState.Idle) {
                                    HomeDashboardScreen(
                                        baseUrl = BuildConfig.STOG_API_BASE_URL,
                                        accessToken = authSession?.accessToken,
                                        nickname = authSession?.nickname,
                                        onLoginRequired = {
                            showLoginMessage("로그인하면 홈 통계와 여행을 확인할 수 있어요.")
                                            destination = AppDestination.LOGIN
                                        },
                                        onSearch = openSearch,
                                        onOpenTravel = {
                                            showingMap = false
                                            destination = AppDestination.TRAVEL
                                        },
                                        onCreateTrip = {
                                            pendingTripDetail = null
                                            showingMap = false
                                            destination = AppDestination.NEW_TRIP
                                        },
                                        onOpenTripDetail = { trip ->
                                            pendingTripDetail = trip
                                            showingMap = false
                                            destination = AppDestination.TRAVEL
                                        },
                                        onOpenDiscover = {
                                            showingMap = false
                                            destination = AppDestination.DISCOVER
                                        },
                                        onOpenUser = {
                                            showingMap = false
                                            destination = AppDestination.USER
                                        },
                                        onOpenStobee = {
                                            openStobee()
                                        },
                                        onCapture = {
                                            destination = AppDestination.CAPTURE
                                        },
                                        onSavePlace = { candidate ->
                                            searchStartedFromMap = false
                                            mapScreenState = MapScreenState()
                                            pendingBookmarkCandidate = candidate
                                            if (authSession == null) {
                showLoginMessage("로그인 후 장소를 여행에 담을 수 있어요.")
                                                destination = AppDestination.LOGIN
                                            } else {
                                                destination = AppDestination.PLACE_SEARCH
                                            }
                                        },
                                        onOpenPlace = { candidate ->
                                            searchCandidates = listOf(candidate)
                                            mapScreenState = mapScreenStateForPlace(
                                                candidate = candidate,
                                                returnToHomeOnPlaceDetailsClose = true,
                                            )
                                            searchStartedFromMap = false
                                            showingMap = true
                                            destination = AppDestination.HOME
                                        },
                                        onRequestLocationPermission = ::requestHomeLocationPermission,
                                        locationRefreshKey = homeLocationRefreshKey,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding)
                                            .statusBarsPadding(),
                                    ) {
                                        when (val state = shareImportState) {
                                            ShareImportUiState.Idle -> ShareImportInbox(
                                                imports = storedImports,
                                                onOpen = { stored ->
                                                    shareImportState = ShareImportUiState.Saved(
                                                        normalized = stored.normalized,
                                                        local = stored.local,
                                                        restored = stored,
                                                    )
                                                },
                                                onOpenMap = { showingMap = true },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            else -> ShareImportScreen(
                                                state = state,
                                                onReviewSaved = ::saveReview,
                                                modifier = Modifier.fillMaxSize(),
                                                baseUrl = BuildConfig.STOG_API_BASE_URL,
                                                accessToken = authSession?.accessToken,
                                                accountId = authSession?.userId?.toString(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        PlaceSearchScreen(
                            baseUrl = BuildConfig.STOG_API_BASE_URL,
                            accessToken = authSession?.accessToken,
                            initialCandidates = searchCandidates,
                            initialPendingCandidate = pendingBookmarkCandidate,
                            onBack = {
                                pendingBookmarkCandidate = null
                                val returnedToMap = searchStartedFromMap
                                searchStartedFromMap = false
                                searchCandidates = emptyList()
                                if (!returnedToMap) {
                                    mapScreenState = MapScreenState()
                                    showingMap = false
                                }
                                destination = AppDestination.HOME
                            },
                            onLoginRequired = { candidate ->
                                pendingBookmarkCandidate = candidate
                showLoginMessage("로그인 후 장소를 여행에 담을 수 있어요.")
                                destination = AppDestination.LOGIN
                            },
                            onBookmarkHandled = { trip ->
                                pendingBookmarkCandidate = null
                                pendingTripDetail = trip
                                showingMap = false
                                destination = AppDestination.TRAVEL
                            },
                            onSearchResults = { searchCandidates = it },
                            onCreateTrip = {
                                pendingTripDetail = null
                                showingMap = false
                                destination = AppDestination.NEW_TRIP
                            },
                            onSelectCandidate = { candidate ->
                                searchCandidates = listOf(candidate)
                                mapScreenState = mapScreenStateForPlace(candidate)
                                searchStartedFromMap = false
                                showingMap = true
                                destination = AppDestination.HOME
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }
                if (
                    initialProfileState == InitialProfileState.IDLE ||
                    initialProfileState == InitialProfileState.COMPLETE
                ) {
                    PostVisitReview(
                        visit = pendingReview,
                        onContinue = { receiptId -> consumeVisitReview(receiptId, openCapture = false) },
                        onCapture = { receiptId -> consumeVisitReview(receiptId, openCapture = true) },
                    )
                    permissionPrompt?.let { prompt ->
                        StogPermissionDialog(
                            prompt = prompt,
                            onDismiss = {
                                permissionPrompt = null
                                permissionPromptPurpose = null
                            },
                            onPrimary = {
                                if (prompt.kind == StogPermissionPromptKind.SETTINGS) {
                                    this@MainActivity.openStogPermissionSettings()
                                } else {
                                    continuePermissionPrompt()
                                }
                            },
                        )
                    }
                    if (pendingTripInviteToken != null && authSession != null) {
                        AlertDialog(
                            onDismissRequest = {
                                if (!joiningTripInvite) {
                                    pendingTripInviteToken = null
                                }
                            },
                            title = { Text("여행 초대") },
                            text = { Text("초대받은 여행에 참여하시겠어요?") },
                            confirmButton = {
                                TextButton(
                                    enabled = !joiningTripInvite,
                                    onClick = {
                                        joinPendingTripInvite(
                                            checkNotNull(authSession).accessToken,
                                        )
                                    },
                                ) {
                                    Text(if (joiningTripInvite) "참여 중..." else "수락")
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !joiningTripInvite,
                                    onClick = { pendingTripInviteToken = null },
                                ) {
                                    Text("나중에")
                                }
                            },
                        )
                    }
                }
            }
        }
        AuthSessionRuntime.retrier = authSessionRetrier
        restoreSession()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionFlowAfterResume()
    }

    private fun permissionPurposeGranted(purpose: PermissionPurpose): Boolean = when (purpose) {
        PermissionPurpose.SHARE_NOTIFICATION -> canPostNotifications()
        PermissionPurpose.RECORDING_LOCATION,
        PermissionPurpose.HOME_LOCATION,
        -> ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        PermissionPurpose.RECORDING_BACKGROUND_LOCATION ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermissionFlowAfterResume() {
        if (!permissionFlowActive || permissionRequestInFlight) return
        val prompt = permissionPrompt
        if (prompt != null) {
            val purpose = permissionPromptPurpose
            if (
                prompt.kind != StogPermissionPromptKind.SETTINGS ||
                purpose == null ||
                !permissionPurposeGranted(purpose)
            ) {
                return
            }
            permissionPrompt = null
            permissionPromptPurpose = null
        }
        when (pendingPermissionPurpose) {
            PermissionPurpose.RECORDING_LOCATION -> {
                val granted =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                if (granted) {
                    pendingPermissionPurpose = PermissionPurpose.SHARE_NOTIFICATION
                    requestRecordingBackgroundLocationOrActivate()
                } else {
                    requestRecordingLocationPermission()
                }
            }
            PermissionPurpose.RECORDING_BACKGROUND_LOCATION -> {
                if (permissionPurposeGranted(PermissionPurpose.RECORDING_BACKGROUND_LOCATION)) {
                    pendingPermissionPurpose = PermissionPurpose.SHARE_NOTIFICATION
                    activateCalendarAndRequestNotification()
                } else {
                    requestRecordingBackgroundLocationOrActivate()
                }
            }
            PermissionPurpose.SHARE_NOTIFICATION -> {
                if (canPostNotifications()) {
                    permissionFlowActive = false
                    pendingShareImportNotificationId?.let(shareImportCoordinator::postNotification)
                } else {
                    requestNotificationPermissionIfNeeded()
                }
            }
            PermissionPurpose.HOME_LOCATION -> {
                if (
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    permissionFlowActive = false
                    homeLocationRefreshKey++
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingTripInviteToken = tripInviteTokenFromUri(intent.data)
        val incomingNaverTicket = naverLoginTicketFromUri(intent.data)
        when {
            incomingNaverTicket != null -> receiveNaverLoginTicket(incomingNaverTicket)
            incomingTripInviteToken != null -> receiveTripInvite(incomingTripInviteToken)
            intent.isShareIntent() -> receiveShare(intent)
            intent.shareImportReviewId() != null -> openShareImportReview(intent.shareImportReviewId()!!)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DESTINATION, destination.savedRoute)
        outState.putBoolean(STATE_SHOWING_MAP, showingMap)
        pendingTripInviteToken?.let { outState.putString(STATE_TRIP_INVITE_TOKEN, it) }
        (shareImportState as? ShareImportUiState.Saved)?.local?.id?.let {
            outState.putString(STATE_SHARE_IMPORT_ID, it)
        }
        pendingBookmarkCandidate?.let(outState::putPlaceSearchCandidate)
        outState.putStringArrayList(
            STATE_SEARCH_CANDIDATES,
            ArrayList(encodePlaceSearchCandidates(searchCandidates)),
        )
    }

    override fun onDestroy() {
        shareImportCoordinator.close()
        super.onDestroy()
    }

    private fun receiveTripInvite(token: String) {
        pendingTripInviteToken = token
        if (tripInviteEntryFor(authSession?.accessToken) == TripInviteEntry.LOGIN) {
            showLoginMessage("로그인하면 여행에 참여할 수 있어요.")
            destination = AppDestination.LOGIN
        } else {
            loginMessage = null
        }
    }

    private fun joinPendingTripInvite(accessToken: String) {
        if (joiningTripInvite) return
        val token = pendingTripInviteToken ?: return
        joiningTripInvite = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PlanningApiClient(BuildConfig.STOG_API_BASE_URL).joinInvite(accessToken, token)
                }
            }.onSuccess {
                joiningTripInvite = false
                pendingTripInviteToken = null
                loginMessage = null
                tripListRefreshKey += 1
                destination = AppDestination.TRAVEL
                showingMap = false
                Toast.makeText(this@MainActivity, "여행에 참여했어요.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                joiningTripInvite = false
                pendingTripInviteToken = null
                destination = AppDestination.TRAVEL
                showingMap = false
                Toast.makeText(
                    this@MainActivity,
                    "초대 링크를 처리하지 못했어요.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun consumeVisitReview(receiptId: Long, openCapture: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            StogDatabase.get(this@MainActivity).visitOutboxDao()
                .consumeReview(receiptId, System.currentTimeMillis())
            if (openCapture) {
                withContext(Dispatchers.Main) { destination = AppDestination.CAPTURE }
            }
        }
    }

    private fun receiveShare(intent: Intent) {
        destination = AppDestination.HOME
        showingMap = false
        requestNotificationPermissionIfNeeded()
        shareImportCoordinator.start(intent) { state ->
            shareImportState = state
            if (state is ShareImportUiState.Saved) {
                storedImports = shareImportCoordinator.loadAll()
                if (canPostNotifications()) {
                    shareImportCoordinator.postNotification(state.local.id)
                } else {
                    pendingShareImportNotificationId = state.local.id
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!canPostNotifications() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionWithPrompt(
                purpose = PermissionPurpose.SHARE_NOTIFICATION,
                permissions = arrayOf(PERMISSION_POST_NOTIFICATIONS),
                granted = false,
                title = "알림 권한이 필요해요",
                detail = "공유한 자료의 저장과 여행 기록 처리 결과를 알림으로 알려드리려면 알림 권한이 필요해요.",
            )
        }
    }

    private fun requestPermissionWithPrompt(
        purpose: PermissionPurpose,
        permissions: Array<String>,
        granted: Boolean,
        title: String,
        detail: String,
        onAlreadyGranted: () -> Unit = {},
    ) {
        permissionFlowActive = true
        when (
            decidePermissionRequest(
                granted = granted,
                shouldShowRationale = permissions.any { shouldShowStogPermissionRationale(it) },
                requestAttempted = purpose in permissionRequestAttempts,
            )
        ) {
            PermissionRequestDecision.ALREADY_GRANTED -> onAlreadyGranted()
            PermissionRequestDecision.SHOW_RATIONALE -> {
                pendingPermissionPurpose = purpose
                permissionPromptPurpose = purpose
                permissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.RATIONALE,
                    title = title,
                    detail = detail,
                )
            }
            PermissionRequestDecision.OPEN_SETTINGS -> {
                pendingPermissionPurpose = purpose
                permissionPromptPurpose = purpose
                permissionPrompt = StogPermissionPrompt(
                    kind = StogPermissionPromptKind.SETTINGS,
                    title = title,
                    detail = "권한이 계속 거부되어 앱에서 다시 요청할 수 없어요. Android 앱 설정에서 필요한 권한을 허용해 주세요.",
                )
            }
        }
    }

    private fun continuePermissionPrompt() {
        val purpose = permissionPromptPurpose ?: return
        permissionPrompt = null
        permissionPromptPurpose = null
        permissionRequestAttempts += purpose
        pendingPermissionPurpose = purpose
        permissionRequestInFlight = true
        when (purpose) {
            PermissionPurpose.SHARE_NOTIFICATION ->
                permissionLauncher.launch(arrayOf(PERMISSION_POST_NOTIFICATIONS))
            PermissionPurpose.RECORDING_LOCATION ->
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            PermissionPurpose.RECORDING_BACKGROUND_LOCATION ->
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            PermissionPurpose.HOME_LOCATION -> Unit
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun openShareImportReview(importId: String) {
        val stored = shareImportCoordinator.load(importId) ?: return
        storedImports = shareImportCoordinator.loadAll()
        shareImportState = ShareImportUiState.Saved(
            normalized = stored.normalized,
            local = stored.local,
            restored = stored,
        )
        destination = AppDestination.HOME
        showingMap = false
    }

    private fun restoreSession() {
        val tokens = authTokenStore.load() ?: return
        isLoggingIn = true
        loginProvider = null
        loginErrorProvider = null
        loginMessage = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val currentUser = authClient.currentUser(tokens.accessToken)
                        StogAuthSession(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            userId = currentUser.id,
                            nickname = currentUser.nickname,
                        )
                    }.getOrElse { error ->
                        if (error is AuthRequestException && error.statusCode == 401) {
                            authClient.refresh(tokens.refreshToken)
                        } else {
                            throw error
                        }
                    }
                }
            }.onSuccess(::completeAuthentication).onFailure {
                authTokenStore.clear()
                authSession = null
                initialProfileState = InitialProfileState.IDLE
                surveySubmissionError = null
                surveySubmitting = false
                surveyResult = null
                isLoggingIn = false
                loginProvider = null
                loginErrorProvider = null
                loginMessage = null
            }
        }
    }

    private fun completeAuthentication(session: StogAuthSession) {
        authSessionRetrier.reset()
        val previousUserId = authTokenStore.load()?.userId
        if (previousUserId != null && previousUserId != session.userId) {
            stopService(Intent(this, TripLocationService::class.java))
        }
        authTokenStore.save(session)
        authSession = session
        surveyResult = null
        isLoggingIn = false
        loginProvider = null
        loginErrorProvider = null
        if (pendingTripInviteToken != null) {
            loginMessage = "여행 참여 확인 중..."
        } else {
            loginMessage = null
        }
        checkInitialProfile(session)
    }

    private fun checkInitialProfile(session: StogAuthSession) {
        initialProfileState = InitialProfileState.CHECKING
        surveySubmissionError = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    profileClient.profile(session.accessToken)
                }
            }.onSuccess { profile ->
                if (authSession?.accessToken != session.accessToken) return@onSuccess
                if (needsInitialSurvey(profile.preferenceScores, profile.travelStyleScores)) {
                    initialProfileState = InitialProfileState.REQUIRED
                    destination = AppDestination.SURVEY
                    showingMap = false
                } else {
                    continueAfterInitialProfile(session)
                }
            }.onFailure {
                if (authSession?.accessToken == session.accessToken) {
                    initialProfileState = InitialProfileState.ERROR
                }
            }
        }
    }

    private fun submitInitialSurvey(answers: Map<String, Int>) {
        val session = authSession ?: return
        surveySubmitting = true
        surveySubmissionError = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    profileClient.submitPrecisionSurvey(session.accessToken, answers)
                }
            }.onSuccess { result ->
                if (authSession?.accessToken != session.accessToken) return@onSuccess
                surveySubmitting = false
                surveyResult = result
                initialProfileState = InitialProfileState.RESULT
                destination = AppDestination.SURVEY_RESULT
            }.onFailure {
                if (authSession?.accessToken == session.accessToken) {
                    surveySubmitting = false
                    surveySubmissionError = "성향을 저장하지 못했어요. 다시 시도해 주세요."
                }
            }
        }
    }

    private fun continueAfterInitialProfile(session: StogAuthSession) {
        surveyResult = null
        initialProfileState = InitialProfileState.COMPLETE
        activateRecordingFor(session)
        if (pendingTripInviteToken != null) {
            loginMessage = null
            destination = AppDestination.TRAVEL
        } else {
            loginMessage = null
            destination = if (
                shouldResumeBookmark(pendingBookmarkCandidate, session.accessToken)
            ) {
                AppDestination.PLACE_SEARCH
            } else {
                AppDestination.HOME
            }
        }
    }

    private fun requestHomeLocationPermission() {
        pendingPermissionPurpose = PermissionPurpose.HOME_LOCATION
        permissionRequestInFlight = true
        permissionFlowActive = true
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun activateRecordingFor(session: StogAuthSession) {
        lifecycleScope.launch(Dispatchers.IO) {
            PendingShareWorkInitializer.schedule(this@MainActivity, session.userId.toString())
            PendingSetLogWorkInitializer.schedule(this@MainActivity, session.userId.toString())
        }
        val locationGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            requestRecordingLocationPermission()
        } else {
            requestRecordingBackgroundLocationOrActivate()
        }
    }

    private fun requestRecordingLocationPermission() {
        requestPermissionWithPrompt(
            purpose = PermissionPurpose.RECORDING_LOCATION,
            permissions = arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
            title = "위치 권한이 필요해요",
            detail = "STOG가 여행 중 위치 변화를 기록하고 방문한 Cell을 계산하려면 위치 권한이 필요해요.",
            onAlreadyGranted = ::requestRecordingBackgroundLocationOrActivate,
        )
    }

    private fun requestRecordingBackgroundLocationOrActivate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionWithPrompt(
                purpose = PermissionPurpose.RECORDING_BACKGROUND_LOCATION,
                permissions = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                granted = false,
                title = "백그라운드 위치 권한이 필요해요",
                detail = "화면을 보지 않는 여행 중에도 위치 기록을 이어가려면 백그라운드 위치 권한이 필요해요.",
                onAlreadyGranted = ::activateCalendarAndRequestNotification,
            )
        } else {
            activateCalendarAndRequestNotification()
        }
    }

    private fun activateCalendarAndRequestNotification() {
        RecordingActivationCoordinator(this).activateCalendar()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications()) {
            requestNotificationPermissionIfNeeded()
        } else {
            permissionFlowActive = false
        }
    }

    private fun expireAuthentication(message: String) {
        stopService(Intent(this, TripLocationService::class.java))
        authTokenStore.clear()
        authSession = null
        authSessionRetrier.reset()
        initialProfileState = InitialProfileState.IDLE
        surveySubmissionError = null
        surveySubmitting = false
        surveyResult = null
        isLoggingIn = false
        loginProvider = null
        loginErrorProvider = null
        showLoginMessage(message)
        destination = AppDestination.LOGIN
    }

    private fun photoAuthenticationRequired() {
        expireAuthentication("로그인 상태가 만료되었어요. 다시 로그인한 뒤 사진을 이어서 올려주세요.")
    }

    private fun logout() {
        stopService(Intent(this, TripLocationService::class.java))
        authTokenStore.clear()
        authSession = null
        authSessionRetrier.reset()
        initialProfileState = InitialProfileState.IDLE
        surveySubmissionError = null
        surveySubmitting = false
        surveyResult = null
        destination = AppDestination.LOGIN
    }

    private fun saveReview(
        id: String,
        decisions: Map<Int, ShareMentionDecision>,
        manualEntries: List<String>,
    ) {
        shareImportCoordinator.saveReview(id, decisions, manualEntries)
        storedImports = shareImportCoordinator.loadAll()
    }

    private fun startKakaoLogin() {
        if (isLoggingIn) return
        beginSocialLogin(LoginProvider.KAKAO)
        val callback: (OAuthToken?, Throwable?) -> Unit = { token, error ->
            when {
                error != null -> {
                    failSocialLogin(
                        provider = LoginProvider.KAKAO,
                        message = "카카오 로그인을 완료하지 못했어요.",
                    )
                }
                token != null -> exchangeKakaoToken(token.accessToken)
                else -> {
                    failSocialLogin(
                        provider = LoginProvider.KAKAO,
                        message = "카카오 로그인 결과를 확인하지 못했어요.",
                    )
                }
            }
        }
        if (UserApiClient.instance.isKakaoTalkLoginAvailable(this)) {
            UserApiClient.instance.loginWithKakaoTalk(this, callback = callback)
        } else {
            UserApiClient.instance.loginWithKakaoAccount(this, callback = callback)
        }
    }

    private fun exchangeKakaoToken(token: String) {
        exchangeSocialToken(LoginProvider.KAKAO, token)
    }

    private fun startNaverLogin() {
        if (isLoggingIn) return
        beginSocialLogin(LoginProvider.NAVER)
        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("${BuildConfig.STOG_API_BASE_URL}/auth/naver/start"),
                ),
            )
        }.onFailure {
            failSocialLogin(
                provider = LoginProvider.NAVER,
                message = "네이버 로그인을 시작하지 못했어요.",
            )
        }
    }

    private fun receiveNaverLoginTicket(ticket: String) {
        beginSocialLogin(LoginProvider.NAVER)
        exchangeSocialToken(LoginProvider.NAVER, ticket)
    }

    private fun naverLoginTicketFromUri(uri: Uri?): String? =
        uri
            ?.takeIf { it.scheme == "stog" && it.host == "oauth" }
            ?.getQueryParameter("ticket")
            ?.takeIf(String::isNotBlank)

    private fun startGoogleLogin() {
        if (isLoggingIn) return
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            failSocialLogin(
                provider = LoginProvider.GOOGLE,
                message = "구글 로그인 설정이 없습니다.",
            )
            return
        }
        beginSocialLogin(LoginProvider.GOOGLE)
        lifecycleScope.launch {
            runCatching {
                val option = GetSignInWithGoogleOption.Builder(
                    BuildConfig.GOOGLE_WEB_CLIENT_ID,
                )
                    .build()
                val result = credentialManager.getCredential(
                    this@MainActivity,
                    GetCredentialRequest.Builder().addCredentialOption(option).build(),
                )
                val credential = result.credential
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                exchangeSocialToken(LoginProvider.GOOGLE, token)
            }.onFailure {
                failSocialLogin(
                    provider = LoginProvider.GOOGLE,
                    message = if (it is GoogleIdTokenParsingException) {
                        "구글 인증 결과를 읽지 못했어요."
                    } else {
                        "구글 로그인을 완료하지 못했어요."
                    },
                )
            }
        }
    }

    private fun exchangeSocialToken(provider: LoginProvider, token: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    authClient.exchangeSocialToken(provider.name.lowercase(), token)
                }
            }.onSuccess(::completeAuthentication).onFailure {
                failSocialLogin(
                    provider = provider,
                    message = "STOG 로그인에 실패했어요. 잠시 후 다시 시도해주세요.",
                )
            }
        }
    }

    private fun beginSocialLogin(provider: LoginProvider) {
        loginProvider = provider
        loginErrorProvider = null
        loginMessage = null
        isLoggingIn = true
    }

    private fun failSocialLogin(
        provider: LoginProvider,
        message: String,
    ) {
        isLoggingIn = false
        loginProvider = null
        loginErrorProvider = provider
        loginMessage = message
    }

    private fun showLoginMessage(message: String) {
        isLoggingIn = false
        loginProvider = null
        loginErrorProvider = null
        loginMessage = message
    }
}

internal data class AppSystemBarAppearance(
    val lightStatusBars: Boolean,
    val lightNavigationBars: Boolean,
)

internal fun appSystemBarAppearance(
    destination: AppDestination,
    showingMap: Boolean,
): AppSystemBarAppearance {
    val darkMapShell = destination == AppDestination.HOME && showingMap
    val darkSetLog = destination == AppDestination.CAPTURE
    val darkNavigation = darkMapShell || darkSetLog || destination in setOf(
        AppDestination.TRAVEL,
        AppDestination.DISCOVER,
        AppDestination.USER,
    )
    return AppSystemBarAppearance(
        lightStatusBars = !(darkMapShell || darkSetLog),
        lightNavigationBars = !darkNavigation,
    )
}

private const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

private enum class PermissionPurpose {
    SHARE_NOTIFICATION,
    RECORDING_LOCATION,
    RECORDING_BACKGROUND_LOCATION,
    HOME_LOCATION,
}

private const val STATE_DESTINATION = "destination"
private const val STATE_SHOWING_MAP = "showing_map"
private const val STATE_TRIP_INVITE_TOKEN = "trip_invite_token"
private const val STATE_SHARE_IMPORT_ID = "share_import_id"
private const val STATE_SEARCH_CANDIDATES = "search_candidates"
private const val STATE_PLACE_EXTERNAL_ID = "place_external_id"
private const val STATE_PLACE_NAME = "place_name"
private const val STATE_PLACE_ADDRESS = "place_address"
private const val STATE_PLACE_LATITUDE = "place_latitude"
private const val STATE_PLACE_LONGITUDE = "place_longitude"
private const val STATE_PLACE_TYPES = "place_types"
private const val STATE_PLACE_OPENING_HOURS = "place_opening_hours"
private const val STATE_PLACE_PHONE = "place_phone"
private const val STATE_PLACE_WEBSITE = "place_website"
private const val STATE_PLACE_GOOGLE_MAPS = "place_google_maps"
private const val STATE_PLACE_PHOTO_NAMES = "place_photo_names"
private const val STATE_PLACE_RATING = "place_rating"
private const val STATE_PLACE_USER_RATING_COUNT = "place_user_rating_count"
private const val STATE_PLACE_BUSINESS_STATUS = "place_business_status"
private const val STATE_PLACE_OPEN_NOW = "place_open_now"
private const val STATE_PLACE_NEXT_CLOSE_TIME = "place_next_close_time"
private const val STATE_PLACE_PROVENANCE_KIND = "place_provenance_kind"
private const val STATE_PLACE_CANONICAL_ID = "place_canonical_id"
private const val STATE_PLACE_SOURCE_TYPE = "place_source_type"
private const val STATE_PLACE_SOURCE_ID = "place_source_id"
private const val STATE_PLACE_CATALOG_STATUS = "place_catalog_status"

private fun Bundle.putPlaceSearchCandidate(candidate: PlaceSearchCandidate) {
    putString(STATE_PLACE_EXTERNAL_ID, candidate.externalId)
    putString(STATE_PLACE_NAME, candidate.name)
    putString(STATE_PLACE_ADDRESS, candidate.address)
    candidate.latitude?.let { putDouble(STATE_PLACE_LATITUDE, it) }
    candidate.longitude?.let { putDouble(STATE_PLACE_LONGITUDE, it) }
    putStringArrayList(STATE_PLACE_TYPES, ArrayList(candidate.types))
    putStringArrayList(STATE_PLACE_OPENING_HOURS, ArrayList(candidate.regularOpeningHours))
    putString(STATE_PLACE_PHONE, candidate.nationalPhoneNumber)
    putString(STATE_PLACE_WEBSITE, candidate.websiteUri)
    putString(STATE_PLACE_GOOGLE_MAPS, candidate.googleMapsUri)
    putStringArrayList(STATE_PLACE_PHOTO_NAMES, ArrayList(candidate.photoNames))
    candidate.rating?.let { putDouble(STATE_PLACE_RATING, it) }
    candidate.userRatingCount?.let { putInt(STATE_PLACE_USER_RATING_COUNT, it) }
    putString(STATE_PLACE_BUSINESS_STATUS, candidate.businessStatus)
    candidate.openNow?.let { putBoolean(STATE_PLACE_OPEN_NOW, it) }
    putString(STATE_PLACE_NEXT_CLOSE_TIME, candidate.nextCloseTime)
    when (val provenance = candidate.provenance) {
        is PlaceSearchProvenance.Canonical -> {
            putString(STATE_PLACE_PROVENANCE_KIND, "canonical")
            putLong(STATE_PLACE_CANONICAL_ID, provenance.placeId)
            putString(STATE_PLACE_SOURCE_TYPE, provenance.sourceType)
            putLong(STATE_PLACE_SOURCE_ID, provenance.sourceId)
            putString(STATE_PLACE_CATALOG_STATUS, provenance.catalogStatus)
        }

        PlaceSearchProvenance.GoogleFallback -> {
            putString(STATE_PLACE_PROVENANCE_KIND, "google_fallback")
        }
    }
}

private fun Bundle.placeSearchCandidate(): PlaceSearchCandidate? {
    val externalId = getString(STATE_PLACE_EXTERNAL_ID) ?: return null
    val name = getString(STATE_PLACE_NAME) ?: return null
    return PlaceSearchCandidate(
        externalId = externalId,
        name = name,
        address = getString(STATE_PLACE_ADDRESS),
        latitude = if (containsKey(STATE_PLACE_LATITUDE)) {
            getDouble(STATE_PLACE_LATITUDE)
        } else {
            null
        },
        longitude = if (containsKey(STATE_PLACE_LONGITUDE)) {
            getDouble(STATE_PLACE_LONGITUDE)
        } else {
            null
        },
        types = getStringArrayList(STATE_PLACE_TYPES).orEmpty(),
        regularOpeningHours = getStringArrayList(STATE_PLACE_OPENING_HOURS).orEmpty(),
        nationalPhoneNumber = getString(STATE_PLACE_PHONE),
        websiteUri = getString(STATE_PLACE_WEBSITE),
        googleMapsUri = getString(STATE_PLACE_GOOGLE_MAPS),
        photoNames = getStringArrayList(STATE_PLACE_PHOTO_NAMES).orEmpty(),
        rating = if (containsKey(STATE_PLACE_RATING)) {
            getDouble(STATE_PLACE_RATING)
        } else {
            null
        },
        userRatingCount = if (containsKey(STATE_PLACE_USER_RATING_COUNT)) {
            getInt(STATE_PLACE_USER_RATING_COUNT)
        } else {
            null
        },
        businessStatus = getString(STATE_PLACE_BUSINESS_STATUS),
        openNow = if (containsKey(STATE_PLACE_OPEN_NOW)) {
            getBoolean(STATE_PLACE_OPEN_NOW)
        } else {
            null
        },
        nextCloseTime = getString(STATE_PLACE_NEXT_CLOSE_TIME),
        provenance = placeSearchProvenance() ?: return null,
    )
}

private fun Bundle.placeSearchProvenance(): PlaceSearchProvenance? {
    return when (getString(STATE_PLACE_PROVENANCE_KIND)) {
        "canonical" -> {
            val placeId = getLong(STATE_PLACE_CANONICAL_ID).takeIf { it > 0L }
            val sourceType = getString(STATE_PLACE_SOURCE_TYPE)?.takeIf(String::isNotBlank)
            val sourceId = getLong(STATE_PLACE_SOURCE_ID).takeIf { it > 0L }
            val catalogStatus = getString(STATE_PLACE_CATALOG_STATUS)?.takeIf(String::isNotBlank)
            if (placeId == null || sourceType == null || sourceId == null || catalogStatus == null) {
                null
            } else {
                PlaceSearchProvenance.Canonical(placeId, sourceType, sourceId, catalogStatus)
            }
        }

        "google_fallback", null -> PlaceSearchProvenance.GoogleFallback
        else -> null
    }
}

private fun Intent.isShareIntent(): Boolean =
    action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
