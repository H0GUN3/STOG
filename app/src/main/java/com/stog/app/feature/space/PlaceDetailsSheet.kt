package com.stog.app.feature.space

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow

@Composable
internal fun PlaceDetailsSheet(
    state: PlaceDetailsState,
    baseUrl: String,
    accessToken: String? = null,
    level: SheetLevel,
    onClose: () -> Unit,
    onCollapse: () -> Unit,
    onSave: (PlaceDetails) -> Unit,
    cellState: CellDetailsState? = null,
    mapContext: MapContext = MapContext.RECORDS,
) {
    if (cellState != null) {
        CellDetailsSheetContent(
            state = cellState,
            baseUrl = baseUrl,
            accessToken = accessToken,
            mapContext = mapContext,
            onClose = onClose,
        )
        return
    }

    when (state) {
        PlaceDetailsState.Idle -> PlaceSheetMessage(
            message = "지도에서 장소를 선택해주세요.",
            onClose = onClose,
        )
        PlaceDetailsState.Loading -> PlaceSheetMessage(
            message = "장소 정보를 불러오는 중이에요.",
            loading = true,
            onClose = onClose,
        )
        is PlaceDetailsState.Failed -> PlaceSheetMessage(
            title = "장소 정보를 불러오지 못했어요.",
            message = state.message,
            onClose = onClose,
        )
        is PlaceDetailsState.Loaded -> Crossfade(
            targetState = level == SheetLevel.Expanded,
            label = "장소 상세 단계",
            modifier = Modifier.fillMaxSize(),
        ) { expanded ->
            if (expanded) {
                ExpandedPlaceDetails(
                    state = state,
                    baseUrl = baseUrl,
                    onCollapse = onCollapse,
                    onSave = { onSave(state.details) },
                )
            } else {
                DefaultPlaceDetails(
                    state = state,
                    baseUrl = baseUrl,
                    onClose = onClose,
                    onSave = { onSave(state.details) },
                )
            }
        }
    }
}

@Composable
private fun DefaultPlaceDetails(
    state: PlaceDetailsState.Loaded,
    baseUrl: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    val details = state.details
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = details.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = StogInk,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = humanizedPlaceCategory(details.types),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StogMuted,
                )
            }
            PlaceBookmarkButton(onClick = onSave)
            PlaceSheetControl(
                kind = PlaceSheetControlKind.Close,
                onClick = onClose,
                contentDescription = "장소 상세 닫기",
            )
        }
        PlaceSummaryLines(
            details = details,
            distanceMeters = state.distanceMeters,
        )
            PlacePhotoRow(
                details = details,
                baseUrl = baseUrl,
            )
    }
}

@Composable
private fun ExpandedPlaceDetails(
    state: PlaceDetailsState.Loaded,
    baseUrl: String,
    onCollapse: () -> Unit,
    onSave: () -> Unit,
) {
    val details = state.details
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box {
            PlacePhotoRow(
                details = details,
                baseUrl = baseUrl,
            )
            PlaceSheetControl(
                kind = PlaceSheetControlKind.Collapse,
                onClick = onCollapse,
                contentDescription = "장소 상세 접기",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = details.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = StogInk,
                    )
                    Text(
                        text = humanizedPlaceCategory(details.types),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StogMuted,
                    )
                }
                PlaceBookmarkButton(
                    onClick = onSave,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            PlaceSummaryLines(
                details = details,
                distanceMeters = state.distanceMeters,
                showAddress = false,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = StogBorder.copy(alpha = 0.65f),
            )
            PlaceInformationSection(details)
            if (details.regularOpeningHours.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = StogBorder.copy(alpha = 0.65f),
                )
                PlaceOpeningHoursSection(details.regularOpeningHours)
            }
        }
    }
}

@Composable
private fun PlaceInformationSection(details: PlaceDetails) {
    val opening = placeOpeningSummary(
        details.businessStatus,
        details.openNow,
        details.nextCloseTime,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("기본 정보", style = MaterialTheme.typography.titleMedium, color = StogInk)
        opening?.let { PlaceInformationRow("영업 상태", it) }
        details.address?.let { PlaceInformationRow("주소", it) }
        details.nationalPhoneNumber?.let { PlaceInformationRow("전화", it) }
        details.websiteUri?.let { PlaceInformationRow("웹사이트", it) }
    }
}

@Composable
private fun PlaceOpeningHoursSection(hours: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("운영 시간", style = MaterialTheme.typography.titleMedium, color = StogInk)
        todayOpeningHours(hours)?.let { PlaceInformationRow("오늘", it, emphasized = true) }
        hours.forEach { line ->
            val label = line.substringBefore(':').trim()
            val value = line.substringAfter(':', missingDelimiterValue = "").trim()
            if (label.isNotBlank() && value.isNotBlank()) {
                PlaceInformationRow(label, value)
            }
        }
    }
}

@Composable
private fun PlaceSheetMessage(
    message: String,
    onClose: () -> Unit,
    title: String? = null,
    loading: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, color = StogInk)
            }
            Text(message, color = StogMuted)
            if (loading) CircularProgressIndicator(color = StogYellow)
        }
        PlaceSheetControl(
            kind = PlaceSheetControlKind.Close,
            onClick = onClose,
            contentDescription = "장소 상세 닫기",
        )
    }
}
