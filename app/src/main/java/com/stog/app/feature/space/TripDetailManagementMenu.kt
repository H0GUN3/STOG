@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stog.app.feature.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted

internal enum class TripManagementAction {
    SHARE_INVITE,
    MANAGE_MEMBERS,
    VIEW_MEMBERS,
    EDIT_TRIP,
    DELETE_TRIP,
    LEAVE_TRIP,
}

internal fun tripManagementActions(isOwner: Boolean): List<TripManagementAction> =
    if (isOwner) {
        listOf(
            TripManagementAction.SHARE_INVITE,
            TripManagementAction.MANAGE_MEMBERS,
            TripManagementAction.EDIT_TRIP,
            TripManagementAction.DELETE_TRIP,
        )
    } else {
        listOf(
            TripManagementAction.SHARE_INVITE,
            TripManagementAction.VIEW_MEMBERS,
            TripManagementAction.LEAVE_TRIP,
        )
    }

@Composable
internal fun TripManagementSheet(
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onAction: (TripManagementAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = StogUiContract.ScreenGutterDp.dp,
                    vertical = StogUiContract.BaseSpacingDp.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Text(
                text = "여행 관리",
                modifier = Modifier.padding(vertical = StogUiContract.BaseSpacingDp.dp),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = StogInk,
            )
            tripManagementActions(isOwner).forEach { action ->
                if (action == TripManagementAction.DELETE_TRIP ||
                    action == TripManagementAction.LEAVE_TRIP
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                MenuActionButton(
                    label = action.label(),
                    destructive = action == TripManagementAction.DELETE_TRIP ||
                        action == TripManagementAction.LEAVE_TRIP,
                    onClick = { onAction(action) },
                )
            }
        }
    }
}

@Composable
private fun MenuActionButton(
    label: String,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .stogTouchTarget(),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) {
                androidx.compose.material3.MaterialTheme.colorScheme.error
            } else {
                StogInk
            },
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun TripMembersSheet(
    memberLabel: String?,
    members: TripMembers?,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onRemove: (TripMember) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = StogUiContract.ScreenGutterDp.dp,
                    vertical = StogUiContract.BaseSpacingDp.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Text(
                text = if (isOwner) "참여자 관리" else "참여자 보기",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = StogInk,
            )
            if (members == null) {
                Text(
                    text = memberLabel ?: "참여자 정보가 없어요.",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = StogMuted,
                )
            } else {
                members.members.forEach { member ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = member.nickname,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = StogUiContract.BaseSpacingDp.dp),
                            color = StogInk,
                        )
                        if (isOwner && member.userId != members.ownerId) {
                            TextButton(
                                onClick = { onRemove(member) },
                                modifier = Modifier.stogTouchTarget(),
                            ) {
                                Text("내보내기")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TripEditDialog(
    trip: TripSummary,
    onDismiss: () -> Unit,
    onSave: (title: String, startDate: String, endDate: String) -> Unit,
) {
    var title by remember(trip.id) { mutableStateOf(trip.title) }
    var startDate by remember(trip.id) { mutableStateOf(trip.plannedStartDate.orEmpty()) }
    var endDate by remember(trip.id) { mutableStateOf(trip.plannedEndDate.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("여행 정보 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("여행 이름") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("시작일 (YYYY-MM-DD)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("종료일 (YYYY-MM-DD)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), startDate.trim(), endDate.trim()) },
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

private fun TripManagementAction.label(): String = when (this) {
    TripManagementAction.SHARE_INVITE -> "초대 링크 공유"
    TripManagementAction.MANAGE_MEMBERS -> "참여자 관리"
    TripManagementAction.VIEW_MEMBERS -> "참여자 보기"
    TripManagementAction.EDIT_TRIP -> "여행 정보 수정"
    TripManagementAction.DELETE_TRIP -> "여행 삭제"
    TripManagementAction.LEAVE_TRIP -> "여행 나가기"
}
