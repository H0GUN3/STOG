package com.stog.app.feature.record

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stog.app.ui.StogMediaPlaceholder
import com.stog.app.ui.StogMediaPlaceholderKind
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.PlanningRequestException
import com.stog.app.feature.space.TripArchiveSummary
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun PhotoArchiveScreen(
    baseUrl: String,
    accessToken: String?,
    viewerId: Long?,
    onBack: () -> Unit,
    onAuthenticationRequired: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTripId: Long? = null,
    initialTrips: List<TripSummary> = emptyList(),
    initialArchive: List<ArchivePhoto> = emptyList(),
    initialSummary: TripArchiveSummary? = null,
    initialDetail: RemotePhoto? = null,
    initialGrants: List<PublicGrant> = emptyList(),
    autoLoad: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(baseUrl) { PhotoApiClient(baseUrl) }
    val planning = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val deviceZone = remember { ZoneId.systemDefault() }
    val deviceLocale = remember { Locale.getDefault() }
    var trips by remember(initialTrips) { mutableStateOf(initialTrips.filter { it.mode == "ended" }) }
    var selectedTrip by remember(initialTrips, selectedTripId) {
        mutableStateOf(selectEndedArchiveTrip(initialTrips, selectedTripId))
    }
    var archive by remember(initialArchive) { mutableStateOf(initialArchive) }
    var archiveSummary by remember(initialSummary) { mutableStateOf(initialSummary) }
    var myPhotosOnly by remember { mutableStateOf(false) }
    var detail by remember(initialDetail) { mutableStateOf(initialDetail) }
    var grants by remember(initialGrants) { mutableStateOf(initialGrants) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<StogSurfaceState?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    if (accessToken.isNullOrBlank()) {
        BackHandler(onBack = onBack)
        PhotoRecordScaffold(
            title = "나의 여행",
            onBack = onBack,
            modifier = modifier,
        ) {
            item {
                StogStatePanel(
                    state = StogSurfaceState.AUTH_EXPIRED,
                    title = "로그인이 필요해요",
                    detail = "로그인하면 나의 여행 사진과 공개 상태를 확인할 수 있어요.",
                    actionLabel = "다시 로그인",
                    onAction = onAuthenticationRequired,
                )
            }
        }
        return
    }

    fun requireToken(): String? = accessToken ?: run {
        onAuthenticationRequired()
        null
    }

    fun loadArchive(trip: TripSummary) {
        val token = requireToken() ?: return
        scope.launch {
            message = null
            loading = true
            failure = null
            runCatching {
                withContext(Dispatchers.IO) {
                    planning.archive(token, trip.id) to api.archive(token, trip.id)
                }
            }.onSuccess { (summary, photos) ->
                if (selectedTrip?.id == trip.id) {
                    archiveSummary = summary
                    archive = photos
                }
            }
                .onFailure { error ->
                    if (archiveFailureRequiresAuthentication(error)) onAuthenticationRequired()
                    else {
                        message = "사진 보관함을 불러오지 못했어요. 다시 시도해주세요."
                        failure = if (error is IOException) StogSurfaceState.OFFLINE else StogSurfaceState.ERROR
                    }
                }
            loading = false
        }
    }

    fun openDetail(item: ArchivePhoto) {
        val token = requireToken() ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val loaded = api.detail(token, item.id)
                    loaded to api.grants(token, item.id)
                }
            }.onSuccess { (loaded, loadedGrants) ->
                detail = loaded
                grants = loadedGrants
            }.onFailure { message = "사진 상세 정보를 불러오지 못했어요." }
        }
    }

    LaunchedEffect(accessToken, reloadKey, autoLoad) {
        if (!autoLoad) return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        loading = true
        failure = null
        runCatching { withContext(Dispatchers.IO) { planning.listTrips(token) } }
            .onSuccess { loaded ->
                trips = loaded.filter { it.mode == "ended" }
                selectedTrip = selectEndedArchiveTrip(loaded, selectedTripId)
            }
            .onFailure {
                message = "여행 목록을 불러오지 못했어요."
                failure = if (it is IOException) StogSurfaceState.OFFLINE else StogSurfaceState.ERROR
            }
        loading = false
    }
    LaunchedEffect(selectedTrip?.id, autoLoad) {
        if (autoLoad) selectedTrip?.let(::loadArchive)
    }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null, onBack = onBack)
    val openedDetail = detail
    PhotoRecordScaffold(title = if (openedDetail == null) "나의 여행" else "사진 상세", onBack = { if (openedDetail == null) onBack() else detail = null }, modifier = modifier) {
        if (openedDetail == null) {
            item {
                Text("여행 사진 보관함", style = MaterialTheme.typography.headlineSmall, color = StogInk)
                Text("사진은 촬영자와 여행 권한에 맞게만 보여요.", color = StogMuted)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("내 사진만 보기", style = MaterialTheme.typography.titleSmall)
                        Text("그룹 여행에서 내가 촬영한 사진만 걸러봐요.", style = MaterialTheme.typography.bodySmall, color = StogMuted)
                    }
                    Switch(
                        checked = myPhotosOnly,
                        onCheckedChange = { myPhotosOnly = it },
                        modifier = Modifier
                            .stogTouchTarget()
                            .testTag("archive_filter_control")
                            .semantics { contentDescription = "내 사진만 보기" },
                    )
                }
            }
            item {
                trips.forEach { trip ->
                    OutlinedButton(
                        onClick = {
                            archive = emptyList()
                            archiveSummary = null
                            selectedTrip = trip
                        },
                        modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                    ) { Text(trip.title) }
                }
            }
            val filtered = viewerId?.let { archivePhotosForViewer(archive, it, myPhotosOnly) }.orEmpty()
            val mapProjection = archiveMapProjection(
                archiveSummary?.trail.orEmpty(),
                filtered,
                deviceZone,
                deviceLocale,
            )
            if (!loading && trips.isEmpty() && failure == null) {
                item {
                    StogStatePanel(
                        state = StogSurfaceState.EMPTY,
                        title = "종료된 여행이 없어요",
                        detail = "여행을 종료하면 동선과 사진이 나의 여행에 정리돼요.",
                    )
                }
            }
            if (loading) {
                item {
                    StogStatePanel(
                        state = StogSurfaceState.LOADING,
                        title = "사진 보관함을 불러오는 중",
                        detail = "선택한 여행의 사진과 공개 상태를 확인하고 있어요.",
                    )
                }
            } else if (failure != null) {
                item {
                    StogStatePanel(
                        state = checkNotNull(failure),
                        title = message ?: "사진 보관함을 불러오지 못했어요",
                        detail = "연결 상태를 확인한 뒤 다시 시도해 주세요.",
                        actionLabel = "다시 시도",
                        onAction = { selectedTrip?.let(::loadArchive) ?: run { reloadKey++ } },
                    )
                }
            } else if (selectedTrip != null) {
                item {
                    val summary = archiveSummary
                    Text("여행 동선", style = MaterialTheme.typography.titleMedium, color = StogInk)
                    Text(
                        if (summary == null) "종료된 여행 기록을 불러오는 중이에요."
                        else "방문 ${summary.visitedCount} · 통과 ${summary.passedCount} · 사진 ${summary.photoCount}",
                        color = StogMuted,
                    )
                }
                item {
                    if (mapProjection.trailPoints.isEmpty() && mapProjection.photoMarkers.isEmpty()) {
                        StogStatePanel(
                            state = StogSurfaceState.EMPTY,
                            title = "지도에 표시할 위치 기록이 없어요",
                            detail = "위치가 확인된 동선이나 사진이 생기면 여기에 표시돼요.",
                        )
                    } else {
                        ArchiveTrailMap(
                            projection = mapProjection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .testTag("archive_trail_map"),
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        StogStatePanel(
                            state = StogSurfaceState.EMPTY,
                            title = "표시할 사진이 없어요",
                            detail = "사진 기록에서 촬영하거나 갤러리 사진을 선택해 보세요.",
                        )
                    }
                }
            }
            filtered.chunked(3).forEachIndexed { rowIndex, rowPhotos ->
                item(key = "archive-grid-$rowIndex") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowPhotos.forEach { archiveItem ->
                            val metadata = archiveItem.readbackMetadata(deviceZone, deviceLocale)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("archive_list_set_log_metadata:${archiveItem.id}")
                                    .semantics(mergeDescendants = true) {
                                        stateDescription = metadata.stateDescription
                                    },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { openDetail(archiveItem) }
                                        .testTag("archive_detail_action:${archiveItem.id}"),
                                ) {
                                    RemoteThumbnail(
                                        archiveItem.thumbnailUrl,
                                        Modifier.fillMaxSize(),
                                    )
                                }
                                Text(
                                    if (archiveItem.cellId == null) "보관함 전용" else "셀 기록 있음",
                                    color = StogMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        repeat(3 - rowPhotos.size) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        } else {
            item {
                RecordSurface {
                    RemoteThumbnail(openedDetail.thumbnailUrl, Modifier.fillMaxWidth().height(220.dp))
                    SetLogReadbackMetadata(
                        metadata = openedDetail.readbackMetadata(deviceZone, deviceLocale),
                        testTag = "archive_detail_set_log_metadata:${openedDetail.id}",
                    )
                    Text(
                        if (openedDetail.cellId == null) "위치 정보가 없어 보관함에만 저장된 사진이에요." else "이 사진은 셀 기록에 연결될 수 있어요.",
                        color = StogMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Text("공개 범위", style = MaterialTheme.typography.titleMedium)
                PhotoVisibility.entries.forEach { visibility ->
                    OutlinedButton(
                        onClick = {
                            val token = requireToken() ?: return@OutlinedButton
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { api.changeVisibility(token, openedDetail.id, visibility) } }
                                    .onSuccess { updated ->
                                        detail = updated
                                        selectedTrip?.let(::loadArchive)
                                    }
                                    .onFailure { message = "공개 범위를 바꾸지 못했어요. 다시 시도해주세요." }
                            }
                        },
                        enabled = openedDetail.visibility != visibility,
                        modifier = Modifier
                            .fillMaxWidth()
                            .stogTouchTarget()
                            .testTag("archive_visibility_action:${visibility.wireValue}"),
                    ) { Text(if (openedDetail.visibility == visibility) "${visibility.label} 선택됨" else visibility.label) }
                }
            }
            item {
                val activeGrant = grants.lastOrNull { it.revokedAt == null }
                RecordSurface {
                    Text("공개 동의와 검토", style = MaterialTheme.typography.titleMedium)
                    Text(publicGrantMessage(openedDetail.visibility, grants, openedDetail.moderationStatus), color = StogMuted)
                    if (openedDetail.visibility == PhotoVisibility.PUBLIC) {
                        if (activeGrant == null) {
                            Button(
                                onClick = {
                                    val token = requireToken() ?: return@Button
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { api.acceptPublicGrant(token, openedDetail.id) } }
                                            .onSuccess { granted -> grants = grants + granted }
                                            .onFailure { message = "공개 동의를 저장하지 못했어요." }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .stogTouchTarget()
                                    .testTag("archive_public_grant_action"),
                                colors = recordPrimaryButtonColors(),
                            ) { Text("공개 동의하기") }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val token = requireToken() ?: return@OutlinedButton
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { api.revokePublicGrant(token, openedDetail.id, activeGrant.version) } }
                                            .onSuccess { revoked -> grants = grants.map { if (it.version == revoked.version) revoked else it } }
                                            .onFailure { message = "공개 동의를 철회하지 못했어요." }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                            ) { Text("공개 동의 ${activeGrant.version} 철회") }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val url = openedDetail.originalUrl ?: run { message = "다운로드 주소를 다시 받아주세요."; return@Button }
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { PhotoDownloadStore(context).download(url, "stog-${openedDetail.id}") }
                            }.onSuccess { message = "사진을 기기 사진 폴더에 저장했어요." }
                                .onFailure { message = "사진을 기기에 저장하지 못했어요." }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .stogTouchTarget()
                        .testTag("archive_download_action"),
                    colors = recordPrimaryButtonColors(),
                ) { Text("기기에 다운로드") }
            }
        }
        message?.let { current -> item { Text(current, color = StogMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
    }
}

internal fun archiveFailureRequiresAuthentication(error: Throwable): Boolean =
    (error as? PhotoRequestException)?.isAuthenticationFailure == true ||
        (error is PlanningRequestException && error.statusCode == 401)

@Composable
internal fun ArchiveTrailMap(
    projection: ArchiveMapProjection,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { MapView(context) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                onCreate(Bundle())
                getMapAsync { map ->
                    map.uiSettings.isMapToolbarEnabled = false
                    googleMap = map
                }
            }
        },
    )

    DisposableEffect(googleMap, projection) {
        val map = googleMap
        if (map != null) {
            map.clear()
            projection.trailSegments.forEach { segment ->
                map.addPolyline(
                    PolylineOptions()
                        .add(
                            LatLng(segment.from.latitude, segment.from.longitude),
                            LatLng(segment.to.latitude, segment.to.longitude),
                        )
                        .color(StogInk.toArgb())
                        .width(if (segment.interpolated) 7f else 10f)
                        .pattern(if (segment.interpolated) listOf(Dash(18f), Gap(12f)) else null),
                )
            }
            projection.photoMarkers.forEach { marker ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(marker.point.latitude, marker.point.longitude))
                        .title(marker.metadata.placeLabel)
                        .snippet(marker.metadata.mapSnippet),
                )
            }
            val boundsPoints = projection.trailPoints + projection.photoMarkers.map(ArchivePhotoMarker::point)
            when (boundsPoints.size) {
                0 -> Unit
                1 -> map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(boundsPoints[0].latitude, boundsPoints[0].longitude),
                        15f,
                    ),
                )
                else -> {
                    val bounds = LatLngBounds.Builder().apply {
                        boundsPoints.forEach { include(LatLng(it.latitude, it.longitude)) }
                    }.build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
                }
            }
        }
        onDispose { map?.clear() }
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
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
}

private enum class ArchiveThumbnailState { LOADING, AVAILABLE, UNAVAILABLE }

@Composable
internal fun RemoteThumbnail(url: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var state by remember(url) {
        mutableStateOf(if (url.isNullOrBlank()) ArchiveThumbnailState.UNAVAILABLE else ArchiveThumbnailState.LOADING)
    }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bitmap = runCatching {
            withContext(Dispatchers.IO) { BitmapFactory.decodeStream(URL(url).openStream()) }
        }.getOrNull()
        state = if (bitmap == null) ArchiveThumbnailState.UNAVAILABLE else ArchiveThumbnailState.AVAILABLE
    }
    Box(
        modifier = modifier
            .background(StogBorder)
            .testTag("archive_thumbnail")
            .semantics {
                stateDescription = state.name.lowercase()
                if (state == ArchiveThumbnailState.AVAILABLE) role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            ArchiveThumbnailState.LOADING -> CircularProgressIndicator(color = StogInk)
            ArchiveThumbnailState.AVAILABLE -> Image(
                bitmap = checkNotNull(bitmap).asImageBitmap(),
                contentDescription = "여행 사진 미리보기",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Crop,
            )
            ArchiveThumbnailState.UNAVAILABLE -> StogMediaPlaceholder(
                kind = StogMediaPlaceholderKind.TOURISM,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
