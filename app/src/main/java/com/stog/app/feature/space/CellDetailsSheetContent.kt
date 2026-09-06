package com.stog.app.feature.space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stog.app.feature.record.RemoteThumbnail
import com.stog.app.feature.record.PhotoApiClient
import com.stog.app.feature.record.RemotePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stog.app.feature.record.SetLogReadbackMetadata
import com.stog.app.feature.record.setLogReadbackMetadata
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun CellDetailsSheetContent(
    state: CellDetailsState,
    baseUrl: String = "",
    accessToken: String? = null,
    mapContext: MapContext = MapContext.RECORDS,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaceSheetControl(
                kind = PlaceSheetControlKind.Close,
                onClick = onClose,
                contentDescription = "셀 상세 닫기",
            )
        }
        when (state) {
            CellDetailsState.Idle -> StogStatePanel(
                state = StogSurfaceState.EMPTY,
                title = "선택한 셀이 없어요",
                detail = "지도에서 셀을 선택하면 공개 정보와 내 기록을 확인할 수 있어요.",
            )
            CellDetailsState.Loading -> StogStatePanel(
                state = StogSurfaceState.LOADING,
                title = "셀 정보를 불러오는 중",
                detail = "공개 정보와 내 기록을 구분해 확인하고 있어요.",
            )
            is CellDetailsState.Failed -> StogStatePanel(
                state = StogSurfaceState.ERROR,
                title = "셀 정보를 불러오지 못했어요",
                detail = state.message,
                action = StogSurfaceAction.NONE,
            )
            is CellDetailsState.Loaded -> LoadedCellDetails(state, baseUrl, accessToken, mapContext)
        }
    }
}

@Composable
private fun LoadedCellDetails(
    state: CellDetailsState.Loaded,
    baseUrl: String,
    accessToken: String?,
    mapContext: MapContext,
) {
    val detail = state.detail
    val deviceZone = remember { ZoneId.systemDefault() }
    val deviceLocale = remember { Locale.getDefault() }
    val photoClient = remember(baseUrl) { PhotoApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    var openedPhoto by remember { mutableStateOf<RemotePhoto?>(null) }
    var photoLoading by remember { mutableStateOf(false) }
    var photoLoadFailed by remember { mutableStateOf(false) }
    var placeInfoExpanded by remember(detail.summary.cellId) { mutableStateOf(false) }
    CellView(
        background = cellBackgroundForMap(mapContext, detail.summary),
        badges = detail.summary.badges,
    )
    Text(
        text = if (mapContext == MapContext.DISCOVERY) "이 지역의 공개 정보" else "이 지역의 기록",
        style = MaterialTheme.typography.titleLarge,
        color = StogInk,
    )
    Text(
        text = if (mapContext == MapContext.DISCOVERY) {
            listOf(
                "명소 ${detail.summary.landmarkCount}",
                "공개 사진 ${detail.summary.publicPhotoCount}",
                "좋아요 ${detail.summary.publicPhotoLikeCount}",
            ).joinToString(" · ")
        } else {
            listOf(
                "명소 ${detail.summary.landmarkCount}",
                "내 방문 ${detail.summary.myVisitCount}",
                "내 사진 ${detail.summary.myPhotoCount}",
            ).joinToString(" · ")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = StogMuted,
    )
    if (state.photos.isEmpty()) {
        Text("표시할 사진이 없어요.", color = StogMuted)
    } else {
        Text(
            text = if (mapContext == MapContext.DISCOVERY) "공개 사진" else "내 사진",
            style = MaterialTheme.typography.titleMedium,
            color = StogInk,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.photos.forEach { photo ->
                Card(
                    modifier = Modifier
                        .width(168.dp)
                        .clickable(enabled = !accessToken.isNullOrBlank()) {
                        val token = accessToken ?: return@clickable
                        photoLoading = true
                        photoLoadFailed = false
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { photoClient.detail(token, photo.id) }
                            }.onSuccess {
                                openedPhoto = it
                            }.onFailure {
                                photoLoadFailed = true
                            }
                            photoLoading = false
                        }
                    },
                    colors = CardDefaults.cardColors(containerColor = StogCanvas),
                    border = BorderStroke(1.dp, StogBorder.copy(alpha = 0.65f)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column {
                        RemoteThumbnail(
                            url = photo.thumbnailUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        )
                        Text(
                            text = photo.takenAt ?: "촬영 날짜 없음",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = StogMuted,
                        )
                    }
                }
            }
        }
        when {
            photoLoading -> CircularProgressIndicator(color = StogInk)
            photoLoadFailed -> Text("원본 사진을 불러오지 못했어요.", color = StogMuted)
            openedPhoto != null -> {
                TextButton(onClick = { openedPhoto = null }) {
                    Text("원본 사진 닫기")
                }
                RemoteThumbnail(
                    url = openedPhoto?.originalUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
            }
        }
        Text(
            text = "기록 상세",
            style = MaterialTheme.typography.titleMedium,
            color = StogInk,
        )
        state.photos.forEach { photo ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StogCanvas),
                border = BorderStroke(1.dp, StogBorder.copy(alpha = 0.65f)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SetLogReadbackMetadata(
                        metadata = setLogReadbackMetadata(
                            placeId = photo.placeId,
                            placeNameSnapshot = photo.placeName,
                            placeResolutionStatus = photo.placeResolutionStatus,
                            note = photo.caption,
                            takenAt = photo.takenAt,
                            visibility = photo.visibility,
                            moderationStatus = photo.moderationStatus,
                            publicationStatus = photo.publicationStatus,
                            accuracyMeters = photo.accuracyMeters,
                            zoneId = deviceZone,
                            locale = deviceLocale,
                        ),
                        testTag = "cell_set_log_metadata:${photo.id}",
                    )
                    Text(
                        text = listOfNotNull(
                            "좋아요 ${photo.likeCount}",
                            photo.visibilityScope?.let { "조회 범위 $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StogMuted,
                    )
                }
            }
        }
    }
    if (
        detail.summary.landmarkName != null ||
            detail.summary.landmarkImageUrl != null
    ) {
        TextButton(onClick = { placeInfoExpanded = !placeInfoExpanded }) {
            Text(if (placeInfoExpanded) "장소 정보 접기" else "장소 정보 보기")
        }
        if (placeInfoExpanded) {
            detail.summary.landmarkName?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = StogInk)
            }
            detail.summary.landmarkImageUrl?.let { url ->
                RemoteThumbnail(
                    url = url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
        }
    }
}
