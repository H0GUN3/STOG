package com.stog.app.feature.plan.share_import

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.stog.app.BuildConfig
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.OutboxState
import com.stog.app.core.database.RetryClass
import com.stog.app.core.database.ShareImportDecisionEntity
import com.stog.app.core.database.OutboxWorkScheduler
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiEntry
import com.stog.app.feature.space.PlaceApiClient
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShareImportInbox(
    imports: List<StoredShareImport>,
    onOpen: (StoredShareImport) -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = imports.groupBy { it.normalized.source }
    LazyColumn(
        modifier = modifier.imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "일정 바구니",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            TextButton(onClick = onOpenMap) {
                Text("지도 보기")
            }
        }
        if (imports.isEmpty()) {
            item {
                Text("공유 담기로 저장한 항목이 여기에 보여요.")
            }
        }
        grouped.forEach { (source, sourceImports) ->
            item {
                Text(
                    "${source.displayName} · ${sourceImports.size}개",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(sourceImports) { stored ->
                ShareImportInboxCard(stored, onClick = { onOpen(stored) })
            }
        }
    }
}

@Composable
fun ShareImportScreen(
    state: ShareImportUiState,
    onReviewSaved: (String, Map<Int, ShareMentionDecision>, List<String>) -> Unit,
    modifier: Modifier = Modifier,
    baseUrl: String = BuildConfig.STOG_API_BASE_URL,
    accessToken: String? = null,
    accountId: String? = null,
    databaseProvider: (android.content.Context) -> StogDatabase = StogDatabase::get,
    initialTrips: List<TripSummary>? = null,
    scheduleSync: (android.content.Context, String, Long) -> Unit = { context, owner, trip ->
        OutboxWorkScheduler(context).enqueueShareImports(owner, trip)
    },
) {
    val normalized = state.normalizedOrNull()
    if (normalized == null) {
        StogStatePanel(
            state = StogSurfaceState.EMPTY,
            title = "확인할 공유 담기가 없어요",
            detail = "외부 앱에서 장소를 공유 담기하면 이곳에서 확인할 수 있어요.",
            modifier = modifier.padding(24.dp),
        )
        return
    }

    if (state is ShareImportUiState.Saved) {
        DurableShareCandidateReview(
            saved = state,
            baseUrl = baseUrl,
            accessToken = accessToken,
            accountId = accountId,
            databaseProvider = databaseProvider,
            initialTrips = initialTrips,
            scheduleSync = scheduleSync,
            modifier = modifier,
        )
        return
    }

    val restored = (state as? ShareImportUiState.Saved)?.restored
    var decisions by remember(normalized, restored?.local?.id) {
        mutableStateOf(restored?.decisions.orEmpty())
    }
    var manualEntries by remember(normalized, restored?.local?.id) {
        mutableStateOf(restored?.manualEntries.orEmpty())
    }
    var manualIndex by remember(normalized) { mutableStateOf<Int?>(null) }
    var manualTitle by remember(normalized) { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "공유 담기",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            val surfaceState = if (state is ShareImportUiState.Saving) {
                StogSurfaceState.LOADING
            } else {
                StogSurfaceState.ERROR
            }
            StogStatePanel(
                state = surfaceState,
                title = state.statusTitle(),
                detail = normalized.title ?: "원본에서 장소 이름을 찾지 못했어요.",
                action = if (surfaceState == StogSurfaceState.ERROR) {
                    StogSurfaceAction.NONE
                } else {
                    com.stog.app.ui.defaultActionFor(surfaceState)
                },
            )
        }
        item {
            Text(
                "${normalized.source.displayName}에서 가져온 내용",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("확인된 장소만 일정에 배치할 수 있어요.")
        }
        itemsIndexed(normalized.mentions) { index, mention ->
            MentionCard(
                mention = mention,
                decision = decisions[index],
                enabled = state is ShareImportUiState.Saved,
                onConfirm = {
                    val next = decisions + (index to ShareMentionDecision.CONFIRMED)
                    decisions = next
                    persistReview(state, next, manualEntries, onReviewSaved)
                },
                onDelete = {
                    val next = decisions + (index to ShareMentionDecision.REJECTED)
                    decisions = next
                    persistReview(state, next, manualEntries, onReviewSaved)
                },
                onManual = {
                    manualTitle = mention.title
                    manualIndex = index
                },
            )
        }
        if (normalized.mentions.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "장소 이름을 찾지 못했어요.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("원본은 저장했어요. 장소를 직접 입력하면 일정에 담을 수 있어요.")
                        Button(
                            onClick = {
                                manualTitle = ""
                                manualIndex = -1
                            },
                            enabled = state is ShareImportUiState.Saved,
                        ) {
                            Text("장소 직접 입력")
                        }
                    }
                }
            }
        }
        items(manualEntries) { title ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("직접 입력한 장소를 일정 바구니에 담았어요.")
                }
            }
        }
        item {
            val confirmed = decisions.values.count {
                it == ShareMentionDecision.CONFIRMED || it == ShareMentionDecision.MANUAL
            } + manualEntries.size
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("일정 바구니", style = MaterialTheme.typography.titleMedium)
                    Text("${normalized.source.displayName} · ${confirmed}개 확인됨")
                    Text(
                        if (confirmed == 0) {
                            "장소를 확인하면 여기에 담겨요."
                        } else {
                            "확인된 장소를 일정에 배치할 수 있어요."
                        },
                    )
                }
            }
        }
        item {
            TravelGuideAiEntry()
        }
    }

    manualIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { manualIndex = null },
            title = { Text("장소 직접 입력") },
            text = {
                TextField(
                    value = manualTitle,
                    onValueChange = { manualTitle = it },
                    label = { Text("장소 이름") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = manualTitle.isNotBlank(),
                    onClick = {
                        if (index >= 0) {
                            val next = decisions + (index to ShareMentionDecision.MANUAL)
                            decisions = next
                            persistReview(state, next, manualEntries, onReviewSaved)
                        } else {
                            val next = manualEntries + manualTitle.trim()
                            manualEntries = next
                            persistReview(state, decisions, next, onReviewSaved)
                        }
                        manualIndex = null
                    },
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { manualIndex = null }) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
private fun DurableShareCandidateReview(
    saved: ShareImportUiState.Saved,
    baseUrl: String,
    accessToken: String?,
    accountId: String?,
    databaseProvider: (android.content.Context) -> StogDatabase,
    initialTrips: List<TripSummary>?,
    scheduleSync: (android.content.Context, String, Long) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val placeClient = remember(baseUrl) { PlaceApiClient(baseUrl) }
    val planningClient = remember(baseUrl) { PlanningApiClient(baseUrl) }
    var trips by remember(saved.local.id) { mutableStateOf<List<TripSummary>>(emptyList()) }
    var review by remember(saved.local.id) {
        mutableStateOf(ShareReviewState(
            importId = saved.local.id,
            intakePersisted = true,
            candidates = saved.normalized.mentions.mapIndexed { index, mention ->
                ShareReviewCandidate(
                    candidateId = "${saved.local.id}:$index",
                    title = mention.title,
                    address = mention.address,
                )
            },
        ))
    }
    var manualName by remember(saved.local.id) { mutableStateOf("") }
    var status by remember(saved.local.id) { mutableStateOf<ShareQueueUiState?>(null) }

    LaunchedEffect(saved.local.id, accessToken, accountId) {
        if (accessToken.isNullOrBlank() || accountId.isNullOrBlank()) return@LaunchedEffect
        val database = databaseProvider(context)
        val decisions = withContext(Dispatchers.IO) {
            database.shareImportDao().decisions(saved.local.id)
        }
        status = shareQueueUiState(decisions)
        val pendingTripIds = decisions
            .filter { it.decision == com.stog.app.core.database.CandidateDecisionState.CONFIRMED && it.tripId != null }
            .mapNotNull { it.tripId }.distinct()
        pendingTripIds.forEach { scheduleSync(context, accountId, it) }
        trips = initialTrips ?: runCatching {
            withContext(Dispatchers.IO) { planningClient.listTrips(accessToken) }
        }.getOrDefault(emptyList())
    }

    LazyColumn(
        modifier = modifier.imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("공유 담기", style = MaterialTheme.typography.headlineSmall)
            Text("확인할 여행을 먼저 선택해 주세요.")
        }
        status?.let { currentStatus ->
            item { ShareQueueStatusPanel(currentStatus) }
        }
        if (accessToken.isNullOrBlank() || accountId.isNullOrBlank()) {
            item {
                StogStatePanel(
                    state = StogSurfaceState.AUTH_EXPIRED,
                    title = "로그인이 필요해요",
                    detail = "로그인 후 확인한 장소를 여행에 담을 수 있어요.",
                    action = StogSurfaceAction.NONE,
                )
            }
        } else if (trips.isEmpty()) {
            item {
                StogStatePanel(
                    state = StogSurfaceState.EMPTY,
                    title = "선택할 여행이 없어요",
                    detail = "내 여행에서 먼저 여행을 만들어 주세요.",
                )
            }
        } else {
            items(trips) { trip ->
                OutlinedButton(
                    onClick = { review = review.selectTrip(trip.id) },
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Text(if (review.selectedTripId == trip.id) "선택됨 · ${trip.title}" else trip.title)
                }
            }
        }
        items(review.candidates, key = { it.candidateId }) { candidate ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                    candidate.address?.let { Text(it) }
                    Text(SHARE_REVIEW_PROMPT)
                    CorrectCandidateControl(candidate) { correctedName, correctedAddress ->
                        review = review.correct(candidate.candidateId, correctedName, correctedAddress)
                    }
                    when (candidate.providerSearchStatus) {
                        ProviderSearchStatus.IDLE -> Button(
                            modifier = Modifier.stogTouchTarget(),
                            onClick = {
                                val start = review.beginProviderSearch(candidate.candidateId)
                                review = start.state
                                start.request?.let { request -> scope.launch {
                                    val result = runCatching { withContext(Dispatchers.IO) { placeClient.search(request.query) } }
                                    review = review.completeProviderSearch(request, result)
                                } }
                            },
                        ) { Text("장소 후보 검색") }
                        ProviderSearchStatus.LOADING -> CircularProgressIndicator()
                        ProviderSearchStatus.EMPTY -> Text("검색 후보가 없어요. 이름을 수정해 다시 검색해 주세요.")
                        ProviderSearchStatus.ERROR -> Text("후보 검색에 실패했어요. 다시 시도해 주세요.")
                        ProviderSearchStatus.LOADED -> candidate.providerMatches.forEach { match ->
                            OutlinedButton(
                                onClick = { review = review.select(candidate.candidateId, match) },
                                modifier = Modifier.stogTouchTarget(),
                            ) {
                                Text(if (candidate.selectedPlace == match) "선택됨 · ${match.name}" else match.name)
                            }
                        }
                    }
                    TextButton(
                        onClick = { review = review.reject(candidate.candidateId) },
                        modifier = Modifier.stogTouchTarget(),
                    ) {
                        Text("아니요 · 후보 거절")
                    }
                }
            }
        }
        item {
            TextField(
                value = manualName,
                onValueChange = { manualName = it },
                label = { Text(if (saved.local.copiedAttachments.isNotEmpty()) "사진 속 장소 이름 직접 입력" else "장소 이름 수정 또는 직접 입력") },
                singleLine = true,
            )
            Button(
                enabled = manualName.isNotBlank(),
                onClick = { review = review.addManual(manualName); manualName = "" },
                modifier = Modifier.stogTouchTarget(),
            ) { Text("수동 후보 추가") }
        }
        item {
            Button(
                enabled = review.canSave() && !accessToken.isNullOrBlank() && !accountId.isNullOrBlank(),
                modifier = Modifier.stogTouchTarget(),
                onClick = {
                    accessToken ?: return@Button
                    val account = accountId ?: return@Button
                    status = ShareQueueUiState.SAVING_LOCAL
                    scope.launch {
                        val retry = runCatching {
                            withContext(Dispatchers.IO) {
                                val database = databaseProvider(context)
                                ShareReviewPersistence(
                                    database,
                                    scheduleSync = { owner, trip -> scheduleSync(context, owner, trip) },
                                ).save(account, review)
                                shareQueueUiState(database.shareImportDao().decisions(saved.local.id))
                            }
                        }.getOrElse {
                            status = ShareQueueUiState.LOCAL_ERROR
                            return@launch
                        }
                        status = retry ?: ShareQueueUiState.QUEUED
                    }
                },
            ) { Text("확인한 장소 담기") }
        }
    }
}

internal enum class ShareQueueUiState {
    SAVING_LOCAL,
    QUEUED,
    RETRYING,
    OFFLINE,
    TERMINAL_ERROR,
    LOCAL_ERROR,
    SYNCED,
}

internal fun shareQueueUiState(
    decisions: List<ShareImportDecisionEntity>,
): ShareQueueUiState? {
    val confirmed = decisions.filter {
        it.decision == com.stog.app.core.database.CandidateDecisionState.CONFIRMED
    }
    if (confirmed.isEmpty()) return null
    if (confirmed.any { it.syncState == OutboxState.TERMINAL }) {
        return ShareQueueUiState.TERMINAL_ERROR
    }
    if (confirmed.any { it.syncState == OutboxState.RETRY && it.retryClass == RetryClass.NETWORK }) {
        return ShareQueueUiState.OFFLINE
    }
    if (confirmed.any {
            it.syncState == OutboxState.RETRY && it.retryClass in setOf(
                RetryClass.RATE_LIMIT,
                RetryClass.SERVER,
            )
        }
    ) {
        return ShareQueueUiState.RETRYING
    }
    if (confirmed.all { it.syncState == OutboxState.ACKNOWLEDGED }) {
        return ShareQueueUiState.SYNCED
    }
    return ShareQueueUiState.QUEUED
}

@Composable
internal fun ShareQueueStatusPanel(state: ShareQueueUiState) {
    val surfaceState = when (state) {
        ShareQueueUiState.SAVING_LOCAL -> StogSurfaceState.LOADING
        ShareQueueUiState.QUEUED,
        ShareQueueUiState.RETRYING,
        ShareQueueUiState.SYNCED,
        -> StogSurfaceState.CONTENT
        ShareQueueUiState.OFFLINE -> StogSurfaceState.OFFLINE
        ShareQueueUiState.TERMINAL_ERROR,
        ShareQueueUiState.LOCAL_ERROR,
        -> StogSurfaceState.ERROR
    }
    StogStatePanel(
        state = surfaceState,
        title = when (state) {
            ShareQueueUiState.SAVING_LOCAL -> "확인한 장소를 기기에 저장 중이에요"
            ShareQueueUiState.QUEUED -> "저장 대기열에 담았어요"
            ShareQueueUiState.RETRYING -> "서버 응답을 기다렸다가 다시 저장해요"
            ShareQueueUiState.OFFLINE -> "연결되면 자동으로 다시 저장해요"
            ShareQueueUiState.TERMINAL_ERROR -> "저장 요청을 다시 확인해 주세요"
            ShareQueueUiState.LOCAL_ERROR -> "기기에 저장하지 못했어요"
            ShareQueueUiState.SYNCED -> "일정 바구니에 저장했어요"
        },
        detail = "원본은 모든 승인 쓰기가 확인될 때까지 기기에 유지돼요.",
        action = StogSurfaceAction.NONE,
    )
}

@Composable
internal fun CorrectCandidateControl(
    candidate: ShareReviewCandidate,
    onCorrect: (String, String?) -> Unit,
) {
    var correctedName by remember(candidate.candidateId, candidate.title) {
        mutableStateOf(candidate.title)
    }
    var correctedAddress by remember(candidate.candidateId, candidate.address) {
        mutableStateOf(candidate.address.orEmpty())
    }
    TextField(
        value = correctedName,
        onValueChange = { correctedName = it },
        label = { Text("장소 이름") },
        singleLine = true,
        modifier = Modifier.testTag("share-correct-name:${candidate.candidateId}"),
    )
    TextField(
        value = correctedAddress,
        onValueChange = { correctedAddress = it },
        label = { Text("주소") },
        singleLine = true,
        modifier = Modifier.testTag("share-correct-address:${candidate.candidateId}"),
    )
    OutlinedButton(
        enabled = correctedName.isNotBlank() && (
            correctedName.trim() != candidate.title ||
                correctedAddress.trim().takeIf(String::isNotBlank) != candidate.address
            ),
        onClick = { onCorrect(correctedName.trim(), correctedAddress.trim().takeIf(String::isNotBlank)) },
        modifier = Modifier.stogTouchTarget().testTag("share-correct-apply:${candidate.candidateId}"),
    ) {
        Text("수정 적용")
    }
}

@Composable
private fun ShareImportInboxCard(
    stored: StoredShareImport,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .stogTouchTarget()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stored.normalized.title ?: "원본 확인 필요")
            Text(stored.status.displayName)
            Text("${stored.normalized.mentions.size}개 장소 확인 필요")
        }
    }
}

@Composable
private fun MentionCard(
    mention: PlaceMention,
    decision: ShareMentionDecision?,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onManual: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(mention.title, style = MaterialTheme.typography.titleMedium)
            mention.address?.let { Text(it) }
            Text(SHARE_REVIEW_PROMPT)
            when (decision) {
                ShareMentionDecision.CONFIRMED ->
                    Text("이 장소를 일정 바구니에 담았어요.")
                ShareMentionDecision.REJECTED,
                ShareMentionDecision.DELETED ->
                    Text("이 후보를 거절했어요. 다른 후보나 직접 입력을 선택할 수 있어요.")
                ShareMentionDecision.MANUAL ->
                    Text("직접 입력한 장소를 일정 바구니에 담았어요.")
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.stogTouchTarget()) {
                        Text("맞아요")
                    }
                    OutlinedButton(onClick = onManual, enabled = enabled, modifier = Modifier.stogTouchTarget()) {
                        Text("직접 입력")
                    }
                    TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.stogTouchTarget()) {
                        Text("아니요")
                    }
                }
            }
        }
    }
}

private fun ShareImportUiState.normalizedOrNull(): NormalizedShareImport? = when (this) {
    ShareImportUiState.Idle -> null
    is ShareImportUiState.Saving -> normalized
    is ShareImportUiState.Saved -> normalized
    is ShareImportUiState.Failed -> normalized
}

private fun ShareImportUiState.statusTitle(): String = when (this) {
    ShareImportUiState.Idle -> "STOG"
    is ShareImportUiState.Saving -> "원본 저장 중 · 장소 확인 중"
    is ShareImportUiState.Saved -> when {
        local.failedAttachments.isNotEmpty() -> "원본 저장됨 · 첨부 확인 필요"
        restored?.status == ShareImportStatus.COMPLETED -> "장소 확인 완료"
        restored?.status == ShareImportStatus.NEEDS_REVIEW -> "장소 확인 필요"
        else -> "저장됨 · 장소 확인 중"
    }
    is ShareImportUiState.Failed -> "저장 실패 · 원본을 다시 확인해 주세요"
}

private fun persistReview(
    state: ShareImportUiState,
    decisions: Map<Int, ShareMentionDecision>,
    manualEntries: List<String>,
    onReviewSaved: (String, Map<Int, ShareMentionDecision>, List<String>) -> Unit,
) {
    val saved = state as? ShareImportUiState.Saved ?: return
    onReviewSaved(saved.local.id, decisions, manualEntries)
}

private val ShareSource.displayName: String
    get() = when (this) {
        ShareSource.KAKAO -> "카카오 지도"
        ShareSource.NAVER -> "네이버 지도"
        ShareSource.INSTAGRAM -> "Instagram"
        ShareSource.UNKNOWN -> "외부 앱"
    }

private val ShareImportStatus.displayName: String
    get() = when (this) {
        ShareImportStatus.SAVED -> "저장됨 · 장소 확인 중"
        ShareImportStatus.NEEDS_REVIEW -> "확인 필요"
        ShareImportStatus.COMPLETED -> "장소 확인 완료"
        ShareImportStatus.FAILED -> "다시 확인 필요"
    }
