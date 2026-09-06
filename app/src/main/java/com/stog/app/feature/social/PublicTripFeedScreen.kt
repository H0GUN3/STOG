package com.stog.app.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PublicTripFeedScreen(
    baseUrl: String,
    accessToken: String?,
    onLoginRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember(baseUrl) { SocialApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PublicTripFeedState>(PublicTripFeedState.Loading) }
    var pendingLikes by remember { mutableStateOf(emptySet<Long>()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            state = PublicTripFeedState.Loading
            state = runCatching {
                withContext(Dispatchers.IO) { client.publicTrips(accessToken) }
            }.fold(
                onSuccess = { PublicTripFeedState.Content(it.items) },
                onFailure = { PublicTripFeedState.Failure },
            )
        }
    }

    fun toggleLike(item: PublicTripFeedItem) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            onLoginRequired()
            return
        }
        if (item.tripId in pendingLikes) return
        pendingLikes = pendingLikes + item.tripId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (item.likedByViewer) client.unlikeTrip(token, item.tripId)
                    else client.likeTrip(token, item.tripId)
                }
            }.onSuccess { like ->
                val current = state as? PublicTripFeedState.Content ?: return@onSuccess
                state = PublicTripFeedState.Content(
                    current.items.map { candidate ->
                        if (candidate.tripId == like.tripId) {
                            candidate.copy(
                                likeCount = like.likeCount,
                                likedByViewer = like.likedByViewer,
                            )
                        } else candidate
                    },
                )
            }.onFailure { message = "좋아요 상태를 바꾸지 못했어요." }
            pendingLikes = pendingLikes - item.tripId
        }
    }

    LaunchedEffect(accessToken) { load() }

    when (val current = state) {
        PublicTripFeedState.Loading -> StogStatePanel(
            state = StogSurfaceState.LOADING,
            title = "공개 여행을 불러오는 중",
            detail = "다른 여행자의 동선을 준비하고 있어요.",
            modifier = modifier,
        )
        PublicTripFeedState.Failure -> StogStatePanel(
            state = StogSurfaceState.OFFLINE,
            title = "공개 여행을 불러오지 못했어요",
            detail = "잠시 후 다시 시도해주세요.",
            actionLabel = "다시 시도",
            onAction = ::load,
            modifier = modifier,
        )
        is PublicTripFeedState.Content -> LazyColumn(
            modifier = modifier.testTag("public_trip_feed"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "공개 여행 동선",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    color = StogInk,
                )
            }
            items(current.items, key = PublicTripFeedItem::tripId) { item ->
                PublicTripFeedCard(
                    item = item,
                    client = client,
                    accessToken = accessToken,
                    likePending = item.tripId in pendingLikes,
                    onLike = { toggleLike(item) },
                    onLoginRequired = onLoginRequired,
                    onMessage = { message = it },
                )
            }
            message?.let { notice ->
                item {
                    Text(
                        notice,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = StogMuted,
                    )
                }
            }
        }
    }
}

private sealed interface PublicTripFeedState {
    data object Loading : PublicTripFeedState
    data object Failure : PublicTripFeedState
    data class Content(val items: List<PublicTripFeedItem>) : PublicTripFeedState
}

@Composable
private fun PublicTripFeedCard(
    item: PublicTripFeedItem,
    client: SocialApiClient,
    accessToken: String?,
    likePending: Boolean,
    onLike: () -> Unit,
    onLoginRequired: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var destinationTripId by remember(item.tripId) { mutableStateOf("") }
    var destinationDay by remember(item.tripId) { mutableStateOf("1") }
    var copyPending by remember(item.tripId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("public_trip_card_${item.tripId}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(item.title, color = StogInk)
            Text(
                "종료 ${item.endedAt} · 일정 ${item.itineraryItemCount}개",
                color = StogMuted,
            )
            item.memberTrails.forEach { trail ->
                val cells = trail.visits.joinToString(" → ") { it.cellId }
                Text("참여자 ${trail.userId}: $cells", color = StogMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onLike,
                    enabled = !likePending,
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Text(if (item.likedByViewer) "좋아요 취소" else "좋아요")
                }
                Text("${item.likeCount}개", modifier = Modifier.padding(top = 12.dp), color = StogMuted)
            }
            OutlinedTextField(
                value = destinationTripId,
                onValueChange = { destinationTripId = it.filter(Char::isDigit) },
                label = { Text("복사할 여행 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().stogTouchTarget(),
            )
            OutlinedTextField(
                value = destinationDay,
                onValueChange = { destinationDay = it.filter(Char::isDigit) },
                label = { Text("복사할 날짜") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().stogTouchTarget(),
            )
            Button(
                onClick = {
                    val token = accessToken
                    val destination = destinationTripId.toLongOrNull()
                    val day = destinationDay.toIntOrNull()
                    if (token.isNullOrBlank()) {
                        onLoginRequired()
                    } else if (destination == null || day == null || day < 1) {
                        onMessage("복사할 여행 ID와 날짜를 입력해주세요.")
                    } else {
                        copyPending = true
                        onMessage("일정을 복사하는 중이에요.")
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    client.copyTrip(
                                        accessToken = token,
                                        sourceTripId = item.tripId,
                                        destinationTripId = destination,
                                        destinationDay = day,
                                        idempotencyKey = UUID.randomUUID().toString(),
                                    )
                                }
                            }.onSuccess {
                                onMessage("일정 ${it.copiedItemCount}개를 복사했어요.")
                            }.onFailure {
                                onMessage("일정을 복사하지 못했어요.")
                            }
                            copyPending = false
                        }
                    }
                },
                enabled = !copyPending,
                modifier = Modifier.fillMaxWidth().stogTouchTarget(),
            ) {
                Text(if (copyPending) "복사 중" else "내 여행에 복사")
            }
        }
    }
}
