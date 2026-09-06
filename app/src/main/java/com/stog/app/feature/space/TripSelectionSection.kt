package com.stog.app.feature.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted

@Composable
internal fun TripSelectionSection(
    availableTrips: List<TripSummary>,
    loadingTrips: Boolean,
    tripLoadMessage: String?,
    showingTripCreation: Boolean,
    tripTitle: String,
    tripStartDateLabel: String?,
    tripEndDateLabel: String?,
    creatingTrip: Boolean,
    buttonColors: ButtonColors,
    onSelect: (TripSummary) -> Unit,
    onShowCreation: () -> Unit,
    onShowSelection: () -> Unit,
    onTitleChange: (String) -> Unit,
    onStartDateClick: () -> Unit,
    onEndDateClick: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (availableTrips.isEmpty()) {
                "장소를 담을 여행을 먼저 만들어주세요."
            } else {
                "장소를 담을 여행을 선택해주세요."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = StogMuted,
        )
        if (loadingTrips) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = StogInk,
                    strokeWidth = 2.dp,
                )
                Text("여행 목록을 불러오는 중이에요.")
            }
        }
        tripLoadMessage?.let {
            Text(it, color = StogMuted)
        }
        if (!showingTripCreation) {
            availableTrips.forEach { trip ->
                OutlinedButton(
                    onClick = { onSelect(trip) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = trip.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            TextButton(
                onClick = onShowCreation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("새 여행 만들기")
            }
        }
        if (showingTripCreation || (!loadingTrips && availableTrips.isEmpty())) {
            if (availableTrips.isNotEmpty()) {
                TextButton(
                    onClick = onShowSelection,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("여행 선택으로 돌아가기")
                }
            }
            OutlinedTextField(
                value = tripTitle,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("여행 이름") },
                placeholder = { Text("전주 여행") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onStartDateClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(tripStartDateLabel ?: "시작일")
                }
                OutlinedButton(
                    onClick = onEndDateClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(tripEndDateLabel ?: "종료일")
                }
            }
            Button(
                onClick = onCreate,
                enabled = !creatingTrip,
                modifier = Modifier.fillMaxWidth(),
                colors = buttonColors,
                shape = RoundedCornerShape(18.dp),
            ) {
                if (creatingTrip) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = StogInk,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("여행 만들기")
                }
            }
        }
    }
}

@Composable
internal fun CurrentTripSummary(
    tripTitle: String,
    basketItemCount: Int,
    onChange: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "선택한 여행: $tripTitle",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "일정 바구니 · 이 화면에서 담은 장소 ${basketItemCount}개",
            style = MaterialTheme.typography.bodyMedium,
            color = StogMuted,
        )
        Text(
            text = "검색 결과를 이 여행에 담을 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
            color = StogMuted,
        )
        TextButton(
            onClick = onChange,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("여행 바꾸기")
        }
    }
}
