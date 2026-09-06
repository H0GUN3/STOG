package com.stog.app.feature.space

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import com.stog.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stog.app.feature.plan.share_import.ShareImportStatus
import com.stog.app.feature.plan.share_import.StoredShareImport
import com.stog.app.feature.plan.trip.TripCalendarDay
import com.stog.app.feature.plan.trip.TripListCategory
import com.stog.app.feature.plan.trip.TripCardContent
import com.stog.app.feature.plan.trip.TripCardData
import com.stog.app.feature.plan.trip.itineraryDayNumber
import com.stog.app.feature.plan.trip.tripCardsFor
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogWarm
import com.stog.app.ui.theme.StogYellow
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TripManagementPanel(
    baseUrl: String,
    accessToken: String?,
    confirmedShareImports: List<StoredShareImport>,
    onLoginRequired: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onCapture: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onArchive: () -> Unit,
    onOpenItinerary: (TripSummary) -> Unit = {},
    onCreateTrip: () -> Unit = {},
    tripListRefreshKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val planningClient = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var cards by remember(accessToken) { mutableStateOf(emptyList<TripCardData>()) }
    var listMessage by remember(accessToken) { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(TripListCategory.ALL) }
    var selectedTrip by remember { mutableStateOf<TripSummary?>(null) }
    var members by remember { mutableStateOf<TripMembers?>(null) }
    var basketItems by remember { mutableStateOf<List<BasketItem>>(emptyList()) }
    var itineraryItems by remember { mutableStateOf<List<ItineraryItem>>(emptyList()) }
    var itineraryChanges by remember { mutableStateOf<List<ItineraryChange>>(emptyList()) }
    var pendingRemoval by remember { mutableStateOf<TripMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var savingItinerary by remember { mutableStateOf(false) }
    var membershipAction by remember { mutableStateOf(false) }
    var addingLinkId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loadFailure by remember { mutableStateOf<StogSurfaceState?>(null) }
    val primaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = StogYellow,
        contentColor = StogInk,
        disabledContainerColor = StogBorder,
        disabledContentColor = StogMuted,
    )
    val eligibleImports = confirmedShareImports.filter { stored ->
        stored.status == ShareImportStatus.COMPLETED &&
            !stored.normalized.originalUrl.isNullOrBlank() &&
            !(stored.normalized.title ?: stored.manualEntries.firstOrNull()).isNullOrBlank()
    }

    suspend fun loadTripData(token: String, trip: TripSummary): TripPanelData = withContext(Dispatchers.IO) {
        TripPanelData(
            members = planningClient.members(token, trip.id),
            basket = planningClient.basket(token, trip.id),
            itinerary = planningClient.itinerary(token, trip.id),
            changes = planningClient.itineraryChanges(token, trip.id),
        )
    }

    LaunchedEffect(accessToken, tripListRefreshKey) {
        val token = accessToken ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                loadTripCards(planningClient, token, planningClient.listTrips(token))
            }
        }.onSuccess {
            cards = it
            listMessage = null
        }.onFailure {
            cards = emptyList()
            listMessage = "저장된 여행을 불러오지 못했어요."
        }
    }

    fun applyTripData(loaded: TripPanelData) {
        members = loaded.members
        basketItems = loaded.basket
        itineraryItems = loaded.itinerary
        itineraryChanges = loaded.changes
    }

    LaunchedEffect(selectedTrip?.id, accessToken) {
        val token = accessToken ?: return@LaunchedEffect
        val trip = selectedTrip ?: return@LaunchedEffect
        loading = true
        message = null
        loadFailure = null
        runCatching { loadTripData(token, trip) }
            .onSuccess(::applyTripData)
            .onFailure {
                members = null
                basketItems = emptyList()
                itineraryItems = emptyList()
                itineraryChanges = emptyList()
                message = "여행 정보를 불러오지 못했어요."
                loadFailure = tripLoadFailure(it)
            }
        loading = false
    }

    fun refreshSelectedTrip() {
        val token = accessToken ?: return
        val trip = selectedTrip ?: return
        scope.launch {
            loadFailure = null
            runCatching { loadTripData(token, trip) }
                .onSuccess(::applyTripData)
                .onFailure {
                    message = "여행 정보를 새로고침하지 못했어요."
                    loadFailure = tripLoadFailure(it)
                }
        }
    }

    pendingRemoval?.let { member ->
        AlertDialog(
            onDismissRequest = { if (!membershipAction) pendingRemoval = null },
            title = { Text("참여자를 내보낼까요?") },
            text = { Text("${member.nickname}님은 이 여행의 일정에 더 이상 접근할 수 없어요.") },
            confirmButton = {
                TextButton(
                    enabled = !membershipAction,
                    onClick = {
                        val token = accessToken ?: return@TextButton
                        val trip = selectedTrip ?: return@TextButton
                        scope.launch {
                            membershipAction = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    planningClient.removeMember(token, trip.id, member.userId)
                                }
                            }.onSuccess {
                                message = "${member.nickname}님을 여행에서 내보냈어요."
                                pendingRemoval = null
                                refreshSelectedTrip()
                            }.onFailure { message = "참여자를 내보내지 못했어요." }
                            membershipAction = false
                        }
                    },
                ) { Text("내보내기") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("취소") } },
        )
    }
    if (confirmLeave) {
        val isOwner = members?.viewerId == members?.ownerId
        AlertDialog(
            onDismissRequest = { if (!membershipAction) confirmLeave = false },
            title = { Text(if (isOwner) "여행을 삭제할까요?" else "여행에서 나갈까요?") },
            text = { Text(if (isOwner) "마지막 참여자일 때만 여행이 삭제돼요." else "이 여행의 일정에 더 이상 접근할 수 없어요.") },
            confirmButton = {
                TextButton(
                    enabled = !membershipAction,
                    onClick = {
                        val token = accessToken ?: return@TextButton
                        val trip = selectedTrip ?: return@TextButton
                        scope.launch {
                            membershipAction = true
                            runCatching {
                                withContext(Dispatchers.IO) { planningClient.leaveTrip(token, trip.id) }
                            }.onSuccess {
                                cards = cards.filterNot { it.trip.id == trip.id }
                                selectedTrip = null
                                members = null
                                confirmLeave = false
                                message = if (isOwner) "여행을 삭제했어요." else "여행에서 나왔어요."
                            }.onFailure { message = "다른 참여자가 남아 있거나 요청을 처리하지 못했어요." }
                            membershipAction = false
                        }
                    },
                ) { Text(if (isOwner) "삭제" else "나가기") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("취소") } },
        )
    }

    val selectedDay: Int? = null

    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "내 여행",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 2.dp),
            )
        }
        if (accessToken.isNullOrBlank()) {
            item {
                StogStatePanel(
                    state = StogSurfaceState.AUTH_EXPIRED,
                    title = "로그인이 필요해요",
                    detail = "로그인하면 여행과 일정을 관리할 수 있어요.",
                    action = StogSurfaceAction.NONE,
                )
            }
            item {
                Button(
                    onClick = onLoginRequired,
                    modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                    colors = primaryButtonColors,
                ) { Text("로그인") }
            }
            return@LazyColumn
        }
        if (loadFailure != null) {
            item {
                val failure = checkNotNull(loadFailure)
                StogStatePanel(
                    state = failure,
                    title = message ?: "여행 정보를 불러오지 못했어요",
                    detail = "연결 상태를 확인한 뒤 다시 시도해 주세요.",
                    actionLabel = if (failure == StogSurfaceState.AUTH_EXPIRED) "다시 로그인" else "다시 시도",
                    onAction = {
                        if (failure == StogSurfaceState.AUTH_EXPIRED) onLoginRequired()
                        else selectedTrip?.let { refreshSelectedTrip() }
                    },
                )
            }
        }
        item {
            TripCategoryTabs(
                selected = selectedCategory,
                onSelected = { selectedCategory = it },
            )
        }
        listMessage?.let { text ->
            item { Text(text, color = StogMuted) }
        }
        item {
            NewTripCard(onClick = onCreateTrip)
        }
        val sections = if (selectedCategory == TripListCategory.ALL) {
            listOf(
                TripListCategory.ACTIVE,
                TripListCategory.UPCOMING,
                TripListCategory.COMPLETED,
            )
        } else {
            listOf(selectedCategory)
        }
        sections.forEach { category ->
            val categoryCards = tripCardsFor(category, cards, today)
            item(key = "trip-section-${category.name}") {
                TripSectionHeader(category = category, count = categoryCards.size)
            }
            if (categoryCards.isEmpty()) {
                item(key = "trip-empty-${category.name}") {
                    Text("해당하는 여행이 없어요.", color = StogMuted)
                }
            } else {
                items(categoryCards, key = { card -> card.trip.id }) { card ->
                    TripCard(
                        card = card,
                        today = today,
                        onClick = {
                            onOpenItinerary(card.trip)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        selectedTrip?.let { trip ->
            item {
                StogStatePanel(
                    StogSurfaceState.CONTENT,
                    trip.title,
                    "${tripStageLabel(trip.mode)} · ${tripDateRangeLabel(trip.plannedStartDate, trip.plannedEndDate).orEmpty()}",
                )
            }
            item {
                Button(
                    onClick = { onOpenItinerary(trip) },
                    modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                    shape = RoundedCornerShape(16.dp),
                    colors = primaryButtonColors,
                ) {
                    Text("일정 구성 상세 열기")
                }
            }
            item { Text("참여자", style = MaterialTheme.typography.titleLarge) }
            members?.members?.forEach { member ->
                item(key = "member-${member.userId}") {
                    MemberRow(
                        member = member,
                        owner = member.userId == members?.ownerId,
                        canRemove = members?.viewerId == members?.ownerId && member.userId != members?.ownerId,
                        onRemove = { pendingRemoval = member },
                    )
                }
            }
            if (members?.viewerId == members?.ownerId) {
                item {
                    Button(
                        enabled = !membershipAction,
                        onClick = {
                            scope.launch {
                                membershipAction = true
                                runCatching { withContext(Dispatchers.IO) { planningClient.createInvite(accessToken, trip.id) } }
                                    .onSuccess { invite ->
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                invite.shareMessage(trip.title),
                                            )
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "초대 링크 보내기"),
                                        )
                                        message = "초대 링크를 보낼 앱을 선택하세요."
                                    }
                                    .onFailure { message = "초대 링크를 만들지 못했어요." }
                                membershipAction = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                        colors = primaryButtonColors,
                    ) { Text("초대 링크 보내기") }
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmLeave = true },
                    enabled = !membershipAction,
                    modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                ) { Text(if (members?.viewerId == members?.ownerId) "여행 삭제" else "여행 나가기") }
            }
            if (trip.mode != "dormant") {
                item {
                    Text(
                        if (trip.mode == "active") "여행 중 실행은 홈에서 확인할 수 있어요. 이 화면에서는 계획을 변경하지 않아요."
                        else "완료된 여행의 기록은 나의 여행에서 확인할 수 있어요.",
                        color = StogMuted,
                    )
                }
            } else if (selectedDay != null) {
                item { Text("${selectedDate.format(DateTimeFormatter.ofPattern("M월 d일"))} · ${selectedDay}일차", style = MaterialTheme.typography.titleLarge) }
                item { Text("일정 바구니", style = MaterialTheme.typography.titleLarge) }
                if (basketItems.isEmpty() && !loading) item { Text("일정 바구니가 비어 있어요.", color = StogMuted) }
                items(basketItems, key = { "basket-${it.id}" }) { basketItem ->
                    BasketItemRow(
                        item = basketItem,
                        inItinerary = itineraryItems.any { it.basketItemId == basketItem.id },
                        selectedDay = selectedDay,
                        onAddToDay = { itineraryItems = addBasketItemToDay(itineraryItems, basketItem.id, selectedDay) },
                    )
                }
                if (eligibleImports.isNotEmpty()) {
                    item { Text("확인한 링크", style = MaterialTheme.typography.titleMedium) }
                    items(eligibleImports, key = { "link-${it.local.id}" }) { stored ->
                        val title = stored.normalized.title ?: stored.manualEntries.first()
                        OutlinedButton(
                            enabled = addingLinkId == null,
                            onClick = {
                                scope.launch {
                                    addingLinkId = stored.local.id
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            planningClient.addLinkToBasket(accessToken, trip.id, stored.normalized.source.name.lowercase(), stored.normalized.originalUrl.orEmpty(), title, null)
                                        }
                                    }.onSuccess { message = "$title 을(를) 일정 바구니에 담았어요."; refreshSelectedTrip() }
                                        .onFailure { message = "링크를 일정 바구니에 담지 못했어요." }
                                    addingLinkId = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                        ) { Text("$title 담기") }
                    }
                }
                item { Text("일정", style = MaterialTheme.typography.titleLarge) }
                val dayItems = itineraryItems.filter { it.dayNumber == selectedDay }.sortedBy { it.orderIndex }
                if (dayItems.isEmpty()) item { Text("일정 바구니에서 이 날짜에 넣어보세요.", color = StogMuted) }
                items(dayItems, key = { "itinerary-${it.basketItemId}" }) { itineraryItem ->
                    val index = dayItems.indexOf(itineraryItem)
                    ItineraryItemRow(
                        title = basketItems.firstOrNull { it.id == itineraryItem.basketItemId }?.title ?: "일정 항목",
                        canMoveUp = index > 0,
                        canMoveDown = index < dayItems.lastIndex,
                        onMoveUp = { itineraryItems = moveDayItem(itineraryItems, itineraryItem.basketItemId, selectedDay, -1) },
                        onMoveDown = { itineraryItems = moveDayItem(itineraryItems, itineraryItem.basketItemId, selectedDay, 1) },
                        onRemove = { itineraryItems = removeItineraryBasketItem(itineraryItems, itineraryItem.basketItemId) },
                    )
                }
                item {
                    Button(
                        enabled = !savingItinerary,
                        onClick = {
                            scope.launch {
                                savingItinerary = true
                                runCatching { withContext(Dispatchers.IO) { planningClient.saveItinerary(accessToken, trip.id, itineraryItems) } }
                                    .onSuccess { itineraryItems = it; message = "일정을 저장했어요."; refreshSelectedTrip() }
                                    .onFailure { message = "일정을 저장하지 못했어요." }
                                savingItinerary = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                        colors = primaryButtonColors,
                    ) { Text(if (savingItinerary) "일정 저장 중" else "일정 저장") }
                }
            }
            if (itineraryChanges.isNotEmpty()) {
                item { Text("일정 변경 이력", style = MaterialTheme.typography.titleLarge) }
                items(itineraryChanges.asReversed(), key = { "change-${it.id}" }) { change ->
                    Text("참여자 ${change.userId} · ${change.createdAt.take(16).replace('T', ' ')}", color = StogMuted)
                }
            }
        }
        message?.let { item { Text(it, color = StogMuted) } }
    }
}

private data class TripPanelData(
    val members: TripMembers,
    val basket: List<BasketItem>,
    val itinerary: List<ItineraryItem>,
    val changes: List<ItineraryChange>,
)

@Composable
private fun TripCategoryTabs(
    selected: TripListCategory,
    onSelected: (TripListCategory) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            TripListCategory.values().forEach { category ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .stogTouchTarget()
                        .clickable { onSelected(category) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = category.label,
                        color = if (category == selected) StogInk else StogMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                if (category == selected) StogYellow else Color.Transparent,
                            ),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StogBorder.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun NewTripCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = StogYellow.copy(alpha = 0.7f),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                        ),
                    ),
                )
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .border(BorderStroke(1.dp, StogYellow), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, color = StogYellow)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("새 여행 만들기", style = MaterialTheme.typography.titleMedium)
            Text("새로운 여행을 계획해보세요", color = StogMuted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = "새 여행 만들기",
            tint = StogMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun TripSectionHeader(
    category: TripListCategory,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(category.label, style = MaterialTheme.typography.titleMedium)
        Text(count.toString(), color = StogWarm, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TripCard(
    card: TripCardData,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = TripCardContent(card = card, today = today, onClick = onClick, modifier = modifier)

@Composable
internal fun MonthlyTripCalendar(
    month: YearMonth,
    days: List<TripCalendarDay>,
    selectedDate: LocalDate,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    startDateLabel: String? = null,
    endDateLabel: String? = null,
) {
    require(days.size == 42)
    Card(
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = "이전 달",
                        tint = StogInk,
                    )
                }
                Text(
                    text = "${month.year}년 ${month.monthValue}월",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = onNextMonth) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "다음 달",
                        tint = StogInk,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEach { weekday ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(weekday, color = StogMuted)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(StogBorder),
            )
            days.chunked(7).forEachIndexed { weekIndex, week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEachIndexed { dayIndex, day ->
                        val edges = tripRangeEdges(days, weekIndex * 7 + dayIndex)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(
                                    color = if (day.tripIds.isEmpty()) Color.Transparent else StogYellow,
                                    shape = calendarRangeShape(edges),
                                )
                                .clickable { onDateSelected(day.date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .then(
                                        if (day.date == selectedDate) {
                                            Modifier.border(1.dp, StogInk, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = day.date.dayOfMonth.toString(),
                                    color = if (day.inDisplayedMonth || day.date == selectedDate) {
                                        StogInk
                                    } else {
                                        StogMuted
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (startDateLabel != null || endDateLabel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(StogBorder),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarBoundaryLabel(
                        label = "시작일",
                        value = startDateLabel ?: "날짜 미정",
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .width(1.dp)
                            .background(StogBorder),
                    )
                    CalendarBoundaryLabel(
                        label = "종료일",
                        value = endDateLabel ?: "날짜 미정",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun calendarRangeShape(edges: TripRangeEdges) = when {
    edges.start && edges.end -> CircleShape
    edges.start -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    edges.end -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
    else -> RoundedCornerShape(0.dp)
}

@Composable
private fun CalendarBoundaryLabel(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = StogMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MemberRow(member: TripMember, owner: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(member.nickname, style = MaterialTheme.typography.titleMedium); if (owner) Text("그룹장", color = StogMuted) }
            if (canRemove) OutlinedButton(onClick = onRemove, modifier = Modifier.stogTouchTarget()) { Text("내보내기") }
        }
    }
}

@Composable
internal fun DateRangeField(
    startDate: String,
    endDate: String,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateRangeSegment(
                label = "시작 날짜",
                value = displayDate(startDate),
                onClick = onStartClick,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .height(40.dp)
                    .width(1.dp)
                    .background(StogBorder),
            )
            DateRangeSegment(
                label = "종료 날짜",
                value = displayDate(endDate),
                onClick = onEndClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DateRangeSegment(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = StogMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = StogInk)
    }
}

@Composable
private fun DateButton(label: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.stogTouchTarget()) { Text(value?.let(::displayDate) ?: label) }
}

internal fun showDatePicker(context: Context, initialDate: String?, onSelected: (String) -> Unit) {
    val initial = runCatching { LocalDate.parse(initialDate) }.getOrNull() ?: LocalDate.now()
    DatePickerDialog(context, { _, year, month, day -> onSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

internal fun isValidDateRange(start: String?, end: String?): Boolean {
    val startDate = runCatching { LocalDate.parse(start) }.getOrNull() ?: return false
    val endDate = runCatching { LocalDate.parse(end) }.getOrNull() ?: return false
    return !endDate.isBefore(startDate)
}

private fun tripLoadFailure(error: Throwable): StogSurfaceState = when {
    error is PlanningRequestException && error.statusCode == 401 -> StogSurfaceState.AUTH_EXPIRED
    error is PlanningRequestException -> StogSurfaceState.ERROR
    error is IOException -> StogSurfaceState.OFFLINE
    else -> StogSurfaceState.ERROR
}

private fun tripDateRangeLabel(start: String?, end: String?): String? =
    if (start == null || end == null) null else "${tripDateText(start)} - ${tripDateText(end)}"
internal fun displayDate(value: String): String =
    runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M월 d일"))
    }.getOrDefault(value)

@Composable
private fun BasketItemRow(item: BasketItem, inItinerary: Boolean, selectedDay: Int, onAddToDay: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(item.category?.let(::humanizedBasketCategory), item.itemType.takeIf { it == "link" }?.let { "링크 블록" }, basketItemStatus(item.status)).joinToString(" · "), color = StogMuted)
            OutlinedButton(onClick = onAddToDay, enabled = !inItinerary, modifier = Modifier.fillMaxWidth().stogTouchTarget()) { Text(if (inItinerary) "일정에 담김" else "${selectedDay}일차에 담기") }
        }
    }
}

@Composable
private fun ItineraryItemRow(title: String, canMoveUp: Boolean, canMoveDown: Boolean, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) { Text("위로") }
                OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) { Text("아래로") }
                OutlinedButton(onClick = onRemove) { Text("빼기") }
            }
        }
    }
}

private fun humanizedBasketCategory(category: String): String = when (category) {
    "cafe" -> "카페"; "restaurant" -> "음식점"; "lodging" -> "숙소"; "shopping" -> "쇼핑"; "attraction" -> "관광지"; else -> "장소"
}
private fun basketItemStatus(status: String): String = when (status) {
    "resolved" -> "위치 확인"; "manual" -> "직접 입력"; "unresolved" -> "위치 미확인"; else -> "확인 필요"
}
