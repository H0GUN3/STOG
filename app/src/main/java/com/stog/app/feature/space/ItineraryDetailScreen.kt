package com.stog.app.feature.space

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.profile.ProfileImageStore
import com.stog.app.feature.plan.trip.TripCardImage
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiApiClient
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiProposal
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiRequestException
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogDarkCanvas
import com.stog.app.ui.theme.StogDarkSurface
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogWhite
import com.stog.app.ui.theme.StogYellow
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ItineraryDetailScreen(
    baseUrl: String,
    accessToken: String?,
    trip: TripSummary,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val travelGuideAi = remember(baseUrl) { TravelGuideAiApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var displayedTrip by remember(trip.id) { mutableStateOf(trip) }
    var members by remember(trip.id) { mutableStateOf<TripMembers?>(null) }
    var basket by remember(trip.id) { mutableStateOf<List<BasketItem>>(emptyList()) }
    var itinerary by remember(trip.id) { mutableStateOf<List<ItineraryItem>>(emptyList()) }
    var day by remember(trip.id) { mutableStateOf(1) }
    var message by remember(trip.id) { mutableStateOf<String?>(null) }
    var coverMessage by remember(trip.id) { mutableStateOf<String?>(null) }
    var saving by remember(trip.id) { mutableStateOf(false) }
    var pendingProposal by remember(trip.id) {
        mutableStateOf<TravelGuideAiProposal?>(null)
    }
    var showManagementSheet by remember(trip.id) { mutableStateOf(false) }
    var showMembersSheet by remember(trip.id) { mutableStateOf(false) }
    var showEditDialog by remember(trip.id) { mutableStateOf(false) }
    var uploadingCover by remember(trip.id) { mutableStateOf(false) }
    var confirmMembershipAction by remember(trip.id) {
        mutableStateOf<TripManagementAction?>(null)
    }
    var pendingRemoval by remember(trip.id) { mutableStateOf<TripMember?>(null) }
    var managementAction by remember(trip.id) { mutableStateOf(false) }
    val imageStore = remember(context) { ProfileImageStore(context) }
    val dayCount = tripDayCount(displayedTrip)
    val isOwner = members?.let { it.viewerId == it.ownerId } == true
    val canOpenManagement = members != null

    BackHandler {
        onBack()
    }

    LaunchedEffect(accessToken, trip.id) {
        val token = accessToken ?: run {
            message = "로그인 후 여행 일정을 확인할 수 있어요."
            return@LaunchedEffect
        }
        val loaded = DetailLoad(
            trip = (async(Dispatchers.IO) {
                runCatching { client.trip(token, trip.id) }
            }).await(),
            members = (async(Dispatchers.IO) {
                runCatching { client.members(token, trip.id) }
            }).await(),
            basket = (async(Dispatchers.IO) {
                runCatching { client.basket(token, trip.id) }
            }).await(),
            itinerary = (async(Dispatchers.IO) {
                runCatching { client.itinerary(token, trip.id) }
            }).await(),
        )
        loaded.trip.onSuccess { displayedTrip = it }
        loaded.members.onSuccess { members = it }
        loaded.basket.onSuccess { basket = it }
        loaded.itinerary.onSuccess { itinerary = it }
        message = if (
            loaded.trip.isSuccess &&
            loaded.members.isSuccess &&
            loaded.basket.isSuccess &&
            loaded.itinerary.isSuccess
        ) {
            null
        } else {
            "일정 일부를 불러오지 못했어요."
        }
    }

    val dayItems = itinerary
        .filter { it.dayNumber == day }
        .sortedBy { it.orderIndex }
    val visibleBasket = basket
    val memberText = members?.members
        ?.joinToString(" · ") { it.nickname }
        ?.takeIf(String::isNotBlank)
    val placeCount = basket.size
    val primaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = StogYellow,
        contentColor = StogInk,
    )
    fun createStobeeItinerary() {
        val token = accessToken ?: run {
            message = "로그인 후 STOBEE 일정을 만들 수 있어요."
            return
        }
        scope.launch {
            saving = true
            val usedRecommendations = basket.isEmpty()
            runCatching {
                withContext(Dispatchers.IO) {
                    val proposal = if (usedRecommendations) {
                        travelGuideAi.previewRecommendedItinerary(
                            token,
                            displayedTrip.id,
                            displayedTrip.title,
                        )
                    } else {
                        travelGuideAi.previewBasketItinerary(token, displayedTrip.id)
                    }
                    val refreshedBasket = if (usedRecommendations) {
                        client.basket(token, displayedTrip.id)
                    } else {
                        null
                    }
                    proposal to refreshedBasket
                }
            }.onSuccess { (proposal, refreshedBasket) ->
                refreshedBasket?.let { basket = it }
                pendingProposal = proposal
                message = if (usedRecommendations) {
                    "STOBEE 추천 장소 ${proposal.actions.size}개로 일정을 만들었어요. 적용 전 확인해 주세요."
                } else {
                    "바구니 ${basket.size}개를 기준으로 일정을 만들었어요. 적용 전 확인해 주세요."
                }
            }.onFailure { error ->
                message = when (error) {
                    is TravelGuideAiRequestException
                        if error.code == "PROPOSAL_SCHEDULE_INCOMPLETE" ->
                        "여행 날짜와 바구니 장소를 먼저 준비해 주세요."
                    else -> "STOBEE 일정을 만들지 못했어요."
                }
            }
            saving = false
        }
    }

    fun applyStobeeItinerary() {
        val token = accessToken ?: return
        val proposal = pendingProposal ?: return
        scope.launch {
            saving = true
            runCatching {
                withContext(Dispatchers.IO) {
                    travelGuideAi.apply(token, proposal)
                    client.itinerary(token, displayedTrip.id)
                }
            }.onSuccess {
                itinerary = it
                pendingProposal = null
                message = "STOBEE 일정을 적용했어요. 기존 고정 일정은 유지했어요."
            }.onFailure {
                message = "STOBEE 일정을 적용하지 못했어요."
            }
            saving = false
        }
    }

    fun shareInvite() {
        val token = accessToken
        if (token == null) {
            message = "로그인 후 초대 링크를 만들 수 있어요."
            return
        }
        scope.launch {
            managementAction = true
            runCatching {
                withContext(Dispatchers.IO) {
                    client.createInvite(token, displayedTrip.id)
                }
            }.onSuccess { invite ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, invite.shareMessage(displayedTrip.title))
                }
                context.startActivity(Intent.createChooser(shareIntent, "초대 링크 보내기"))
                message = "초대 링크를 보낼 앱을 선택하세요."
            }.onFailure {
                message = "초대 링크를 만들지 못했어요."
            }
            managementAction = false
        }
    }

    fun saveEditedTrip(title: String, startDate: String, endDate: String) {
        val start = parseLocalDate(startDate)
        val end = parseLocalDate(endDate)
        if (title.isBlank() || start == null || end == null || end.isBefore(start)) {
            message = "여행 이름과 올바른 날짜 범위를 입력해 주세요."
            return
        }
        val token = accessToken
        if (token == null) {
            message = "로그인 후 여행 정보를 수정할 수 있어요."
            return
        }
        scope.launch {
            managementAction = true
            runCatching {
                withContext(Dispatchers.IO) {
                    client.updateTrip(token, displayedTrip.id, title, startDate, endDate)
                }
            }.onSuccess { updated ->
                displayedTrip = displayedTrip.copy(
                    title = updated.title,
                    plannedStartDate = updated.plannedStartDate,
                    plannedEndDate = updated.plannedEndDate,
                )
                showEditDialog = false
                message = "여행 정보를 수정했어요."
            }.onFailure {
                message = "여행 정보를 수정하지 못했어요."
            }
            managementAction = false
        }
    }

    fun replaceCover(uri: Uri?) {
        val token = accessToken
        if (uri == null || token.isNullOrBlank() || uploadingCover) return
        scope.launch {
            uploadingCover = true
            coverMessage = null
            message = null
            var file: File? = null
            runCatching {
                file = withContext(Dispatchers.IO) { imageStore.prepare(uri) }
                withContext(Dispatchers.IO) {
                    client.uploadTripCover(token, displayedTrip.id, requireNotNull(file))
                    client.trip(token, displayedTrip.id)
                }
            }.onSuccess { updated ->
                displayedTrip = updated
                coverMessage = "여행 대표 이미지를 변경했어요."
            }.onFailure { error ->
                Log.e("STOG.Upload", "trip cover upload failed", error)
                coverMessage = "여행 대표 이미지를 변경하지 못했어요."
            }
            withContext(Dispatchers.IO) { file?.delete() }
            uploadingCover = false
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
        ::replaceCover,
    )

    LazyColumn(
        modifier = modifier
            .background(StogCanvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            horizontal = StogUiContract.ScreenGutterDp.dp,
            vertical = StogUiContract.BaseSpacingDp.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
    ) {
        item {
            DetailTopBar(
                title = displayedTrip.title,
                onBack = onBack,
                onOverflow = {
                    if (canOpenManagement) {
                        showManagementSheet = true
                    } else {
                        message = "여행 권한을 확인하는 중이에요."
                    }
                },
            )
        }
        item {
            TripCoverImage(
                trip = displayedTrip,
                canEdit = isOwner,
                uploading = uploadingCover,
                onPickImage = { coverPicker.launch("image/*") },
            )
        }
        coverMessage?.let { text ->
            item {
                Text(
                    text = text,
                    color = StogMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        item {
            TripOverviewCard(
                trip = displayedTrip,
                memberText = memberText,
                placeCount = placeCount,
                itineraryCount = itinerary.size,
            )
        }
        item {
            BasketSection(
                items = visibleBasket,
                itinerary = itinerary,
                day = day,
                onAdd = { item ->
                    itinerary = addItineraryItemToDay(itinerary, item.id, day)
                },
                onRemove = { item ->
                    val token = accessToken
                    if (token == null) {
                        message = "로그인 후 바구니를 수정할 수 있어요."
                    } else {
                        scope.launch {
                            managementAction = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    client.removeBasketItem(token, displayedTrip.id, item.id)
                                }
                            }.onSuccess {
                                basket = basket.filterNot { it.id == item.id }
                                itinerary = itinerary.filterNot { it.basketItemId == item.id }
                                message = "바구니에서 삭제했어요."
                            }.onFailure {
                                message = "바구니 항목을 삭제하지 못했어요."
                            }
                            managementAction = false
                        }
                    }
                },
            )
        }
        item {
            Text(
                text = "여행 일정",
                style = MaterialTheme.typography.titleMedium,
                color = StogInk,
            )
        }
        item {
            DayTabs(
                trip = displayedTrip,
                dayCount = dayCount,
                selectedDay = day,
                onDaySelected = { day = it },
            )
        }
        if (dayItems.isEmpty()) {
            item {
                EmptyDayCard(day = day)
            }
        } else {
            items(
                items = dayItems,
                key = { "${it.basketItemId}-${it.dayNumber}-${it.orderIndex}" },
            ) { item ->
                val basketItem = basket.firstOrNull { it.id == item.basketItemId }
                ScheduleRow(
                    item = item,
                    basketItem = basketItem,
                    onRemove = {
                        if (!item.fixed) {
                            itinerary = removeItineraryItem(itinerary, item)
                        }
                    },
                )
            }
        }
        message?.let { text ->
            item {
                Text(
                    text = text,
                    color = StogMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        pendingProposal?.let { proposal ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                    color = StogCanvas,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StogBorder),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "STOBEE 제안 ${proposal.actions.size}개",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "고정 일정은 유지하고, 나머지 바구니 장소를 일정에 배치했어요.",
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = ::applyStobeeItinerary,
                                enabled = !saving,
                                modifier = Modifier.weight(1f).stogTouchTarget(),
                                colors = primaryButtonColors,
                            ) {
                                Text("적용")
                            }
                            OutlinedButton(
                                onClick = { pendingProposal = null },
                                enabled = !saving,
                                modifier = Modifier.weight(1f).stogTouchTarget(),
                            ) {
                                Text("닫기")
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (basket.isEmpty()) {
                        createStobeeItinerary()
                    } else {
                        val token = accessToken ?: return@Button
                        scope.launch {
                            saving = true
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    client.saveItinerary(token, trip.id, itinerary)
                                }
                            }.onSuccess {
                                itinerary = it
                                message = "일정을 저장했어요."
                            }.onFailure {
                                message = "일정을 저장하지 못했어요."
                            }
                            saving = false
                        }
                    }
                },
                enabled = canCreateStobeeItinerary(accessToken, saving),
                modifier = Modifier
                    .fillMaxWidth()
                    .stogTouchTarget(),
                shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                colors = primaryButtonColors,
            ) {
                Text(if (saving) "저장 중" else "일정 저장")
            }
        }
    }

    if (showManagementSheet) {
        TripManagementSheet(
            isOwner = isOwner,
            onDismiss = { showManagementSheet = false },
            onAction = { action ->
                showManagementSheet = false
                when (action) {
                    TripManagementAction.SHARE_INVITE -> shareInvite()
                    TripManagementAction.MANAGE_MEMBERS,
                    TripManagementAction.VIEW_MEMBERS -> showMembersSheet = true
                    TripManagementAction.EDIT_TRIP -> showEditDialog = true
                    TripManagementAction.DELETE_TRIP,
                    TripManagementAction.LEAVE_TRIP -> confirmMembershipAction = action
                }
            },
        )
    }
    if (showMembersSheet) {
        TripMembersSheet(
            memberLabel = memberText,
            members = members,
            isOwner = isOwner,
            onDismiss = { showMembersSheet = false },
            onRemove = {
                showMembersSheet = false
                pendingRemoval = it
            },
        )
    }
    if (showEditDialog) {
        TripEditDialog(
            trip = displayedTrip,
            onDismiss = { showEditDialog = false },
            onSave = ::saveEditedTrip,
        )
    }
    if (confirmMembershipAction != null) {
        val action = confirmMembershipAction!!
        val deleting = action == TripManagementAction.DELETE_TRIP
        AlertDialog(
            onDismissRequest = { if (!managementAction) confirmMembershipAction = null },
            title = { Text(if (deleting) "여행을 삭제할까요?" else "여행에서 나갈까요?") },
            text = {
                Text(
                    if (deleting) {
                        "다른 참여자가 남아 있으면 먼저 참여자를 정리해야 삭제할 수 있어요."
                    } else {
                        "이 여행의 일정과 바구니에 더 이상 접근할 수 없어요."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !managementAction,
                    onClick = {
                        val token = accessToken
                        if (token == null) {
                            message = "로그인 후 여행을 관리할 수 있어요."
                            confirmMembershipAction = null
                        } else {
                            scope.launch {
                                managementAction = true
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        if (deleting) {
                                            client.deleteTrip(token, displayedTrip.id)
                                        } else {
                                            client.leaveTrip(token, displayedTrip.id)
                                        }
                                    }
                                }.onSuccess {
                                    confirmMembershipAction = null
                                    onBack()
                                }.onFailure {
                                    message = if (deleting) {
                                        "다른 참여자가 남아 있거나 삭제하지 못했어요."
                                    } else {
                                        "여행에서 나가지 못했어요."
                                    }
                                    confirmMembershipAction = null
                                }
                                managementAction = false
                            }
                        }
                    },
                ) {
                    Text(if (deleting) "삭제" else "나가기")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !managementAction,
                    onClick = { confirmMembershipAction = null },
                ) {
                    Text("취소")
                }
            },
        )
    }
    if (pendingRemoval != null) {
        val member = pendingRemoval!!
        AlertDialog(
            onDismissRequest = { if (!managementAction) pendingRemoval = null },
            title = { Text("${member.nickname}님을 내보낼까요?") },
            text = { Text("이 여행의 일정과 바구니에 더 이상 접근할 수 없어요.") },
            confirmButton = {
                TextButton(
                    enabled = !managementAction,
                    onClick = {
                        val token = accessToken
                        if (token == null || members == null) {
                            message = "로그인 후 참여자를 관리할 수 있어요."
                            pendingRemoval = null
                        } else {
                            scope.launch {
                                managementAction = true
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        client.removeMember(token, displayedTrip.id, member.userId)
                                    }
                                }.onSuccess {
                                    members = members?.copy(
                                        members = members!!.members.filterNot {
                                            it.userId == member.userId
                                        },
                                    )
                                    pendingRemoval = null
                                    showMembersSheet = true
                                }.onFailure {
                                    message = "참여자를 내보내지 못했어요."
                                    pendingRemoval = null
                                }
                                managementAction = false
                            }
                        }
                    },
                ) {
                    Text("내보내기")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !managementAction,
                    onClick = { pendingRemoval = null },
                ) {
                    Text("취소")
                }
            },
        )
    }
}

private data class DetailLoad(
    val trip: Result<TripSummary>,
    val members: Result<TripMembers>,
    val basket: Result<List<BasketItem>>,
    val itinerary: Result<List<ItineraryItem>>,
)

@Composable
private fun TripCoverImage(
    trip: TripSummary,
    canEdit: Boolean,
    uploading: Boolean,
    onPickImage: () -> Unit,
) {
    var bitmap by remember(trip.coverImageUrl) { mutableStateOf<Bitmap?>(null) }
    var showEditAction by remember(trip.id) { mutableStateOf(false) }
    val editClickLabel = if (showEditAction) {
        "여행 대표 이미지 변경 버튼 숨기기"
    } else {
        "여행 대표 이미지 변경 버튼 표시"
    }
    LaunchedEffect(trip.coverImageUrl) {
        bitmap = trip.coverImageUrl?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching { loadPlacePhoto(url) }
                    .onFailure { error ->
                        Log.w("STOG.Image", "trip cover image load failed", error)
                    }
                    .getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(StogUiContract.LargeRadiusDp.dp))
            .then(
                if (canEdit) {
                    Modifier.clickable(
                        enabled = !uploading,
                        onClickLabel = editClickLabel,
                        role = Role.Button,
                    ) {
                        showEditAction = !showEditAction
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Image(
                painter = painterResource(tripImageResource(trip.title)),
                contentDescription = "${trip.title} 대표 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = "${trip.title} 대표 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (canEdit && showEditAction && !uploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StogDarkCanvas.copy(alpha = 0.28f)),
            )
            IconButton(
                onClick = {
                    showEditAction = false
                    onPickImage()
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(StogYellow, CircleShape)
                    .stogTouchTarget()
                    .semantics { contentDescription = "여행 대표 이미지 변경" },
            ) {
                Text(
                    text = "+",
                    color = StogInk,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StogDarkCanvas.copy(alpha = 0.38f)),
            )
            Surface(
                color = StogDarkSurface,
                shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(color = StogYellow)
                    Text("대표 이미지를 저장하는 중이에요.", color = StogWhite)
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    onOverflow: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.stogTouchTarget(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = "내 여행으로 돌아가기",
                tint = StogInk,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = StogInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = onOverflow,
            modifier = Modifier.stogTouchTarget(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "여행 관리 메뉴",
                tint = StogInk,
            )
        }
    }
}

@Composable
private fun TripOverviewCard(
    trip: TripSummary,
    memberText: String?,
    placeCount: Int,
    itineraryCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StogUiContract.LargeRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StogBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trip.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = StogInk,
                )
                Surface(
                    color = StogYellow,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = detailStageLabel(trip.mode),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = StogInk,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            memberText?.let {
                Text(it, color = StogMuted, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tripReferenceDateRangeText(trip), color = StogInk)
                Text("·", color = StogBorder)
                Text(tripDestinationText(trip), color = StogInk)
                Text("·", color = StogBorder)
                Text(tripDurationText(trip), color = StogInk)
            }
            HorizontalDivider(color = StogBorder)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("장소 ${placeCount}개", color = StogInk)
                Text("저장된 일정 ${itineraryCount}개", color = StogInk)
            }
        }
    }
}

@Composable
private fun BasketSection(
    items: List<BasketItem>,
    itinerary: List<ItineraryItem>,
    day: Int,
    onAdd: (BasketItem) -> Unit,
    onRemove: (BasketItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp)) {
        Text(
            text = "일정 바구니",
            style = MaterialTheme.typography.titleMedium,
            color = StogInk,
        )
        if (items.isEmpty()) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
                Text(
                    text = "저장된 장소가 아직 없어요.",
                    modifier = Modifier.padding(16.dp),
                    color = StogMuted,
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
                contentPadding = PaddingValues(end = StogUiContract.BaseSpacingDp.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    BasketCard(
                        item = item,
                        included = itinerary.any { it.basketItemId == item.id },
                        day = day,
                        onAdd = { onAdd(item) },
                        onRemove = { onRemove(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BasketCard(
    item: BasketItem,
    included: Boolean,
    day: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.width(144.dp),
        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StogBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Text(
                text = basketCardTitle(item),
                style = MaterialTheme.typography.titleSmall,
                color = StogInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = basketCardSubtitle(item),
                color = StogMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TripCardImage(
                url = item.imageUrl,
                fallbackRes = itineraryImageResource(item.title, item.category),
                contentDescription = "${item.title} 이미지",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(StogUiContract.SmallRadiusDp.dp)),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onAdd,
                    enabled = !included,
                    modifier = Modifier
                        .weight(1f)
                        .stogTouchTarget(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = if (included) "담김" else "+ 일정에 추가",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "${item.title} 바구니에서 삭제",
                        tint = StogMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTabs(
    trip: TripSummary,
    dayCount: Int,
    selectedDay: Int,
    onDaySelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
    ) {
        (1..dayCount).forEach { candidate ->
            val selected = candidate == selectedDay
            Surface(
                modifier = Modifier
                    .width(92.dp)
                    .stogTouchTarget()
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    },
                onClick = { onDaySelected(candidate) },
                shape = RoundedCornerShape(24.dp),
                color = if (selected) StogYellow else StogCanvas,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (selected) StogYellow else StogBorder,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "DAY $candidate",
                        color = if (selected) StogInk else StogMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = tripDayDateText(trip, candidate),
                        color = if (selected) StogInk else StogMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    item: ItineraryItem,
    basketItem: BasketItem?,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StogBorder),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            TripCardImage(
                url = item.imageUrl,
                fallbackRes = itineraryImageResource(
                    basketItem?.title ?: item.title.orEmpty(),
                    basketItem?.category,
                ),
                contentDescription = "${basketItem?.title ?: "장소"} 이미지",
                modifier = Modifier
                    .size(width = 80.dp, height = 58.dp)
                    .clip(RoundedCornerShape(StogUiContract.SmallRadiusDp.dp)),
            )
            Text(
                text = plannedArrivalText(item.plannedArrival),
                color = StogYellow,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.width(48.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = basketItem?.title ?: "장소 ${item.basketItemId}",
                    color = StogInk,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = basketItem?.category?.let(::basketCategoryLabel)
                        ?: "장소",
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                enabled = !item.fixed,
                modifier = Modifier.stogTouchTarget(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "${basketItem?.title ?: "장소"} 일정에서 삭제",
                    tint = if (item.fixed) StogBorder else StogMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyDayCard(day: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
        colors = CardDefaults.cardColors(containerColor = StogSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Text(
            text = "${day}일차에 담긴 일정이 없어요.",
            modifier = Modifier.padding(16.dp),
            color = StogMuted,
        )
    }
}

internal fun addItineraryItemToDay(
    items: List<ItineraryItem>,
    basketItemId: Long,
    day: Int,
): List<ItineraryItem> {
    if (items.any { it.basketItemId == basketItemId }) return items
    val nextOrder = items.filter { it.dayNumber == day }.maxOfOrNull { it.orderIndex }
        ?.plus(1) ?: 0
    return items + ItineraryItem(
        basketItemId = basketItemId,
        dayNumber = day,
        orderIndex = nextOrder,
        plannedArrival = null,
        plannedDurationMin = null,
    )
}

internal fun removeItineraryItem(
    items: List<ItineraryItem>,
    target: ItineraryItem,
): List<ItineraryItem> {
    val remaining = items.filterNot {
        it.basketItemId == target.basketItemId && it.dayNumber == target.dayNumber
    }
    return normalizeDayOrder(remaining, target.dayNumber)
}

private fun normalizeDayOrder(
    items: List<ItineraryItem>,
    day: Int,
): List<ItineraryItem> {
    val normalized = items
        .filter { it.dayNumber == day }
        .sortedBy { it.orderIndex }
        .mapIndexed { index, item -> item.copy(orderIndex = index) }
        .associateBy { it.basketItemId }
    return items.map { normalized[it.basketItemId] ?: it }
}

private fun basketCardTitle(item: BasketItem): String = when (item.id) {
    101L -> "동부 코스"
    102L -> "맛집 모음"
    103L -> "숙소 주변"
    else -> item.title
}

private fun basketCardSubtitle(item: BasketItem): String = when (item.id) {
    101L -> "성산 · 섭지코지 · 카페"
    102L -> "흑돼지 · 해물라면"
    103L -> "숙소 · 산책 · 카페"
    else -> item.category?.let(::basketCategoryLabel) ?: "장소"
}

private fun basketCategoryLabel(category: String): String = when (category) {
    "cafe" -> "카페"
    "restaurant" -> "맛집"
    "lodging" -> "숙소"
    "transport" -> "교통 · 이동"
    "attraction" -> "관광지"
    "beach" -> "해변 · 산책"
    else -> category
}

private fun detailStageLabel(mode: String): String = when (mode) {
    "active" -> "진행 중"
    "dormant" -> "예정"
    "ended" -> "완료"
    else -> "상태 확인 필요"
}

private fun tripDayCount(trip: TripSummary): Int {
    val start = trip.plannedStartDate?.let(::parseLocalDate) ?: return 1
    val end = trip.plannedEndDate?.let(::parseLocalDate) ?: start
    return (java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1)
        .coerceAtLeast(1)
}

private fun parseLocalDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value)
}.getOrNull()

internal fun canCreateStobeeItinerary(
    accessToken: String?,
    saving: Boolean,
): Boolean = !saving && accessToken != null
