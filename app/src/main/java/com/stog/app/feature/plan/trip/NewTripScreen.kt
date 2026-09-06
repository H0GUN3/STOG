@file:Suppress("FunctionName")

package com.stog.app.feature.plan.trip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.record.PhotoDerivativeStore
import com.stog.app.feature.record.PhotoSource
import com.stog.app.feature.record.PreparedPhoto
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogWarm
import com.stog.app.ui.theme.StogYellow
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class TripCreationFailure {
    TRIP_CREATION,
    COVER_UPLOAD,
}

internal fun tripCreationFailureFor(createdTripId: Long?): TripCreationFailure =
    if (createdTripId == null) {
        TripCreationFailure.TRIP_CREATION
    } else {
        TripCreationFailure.COVER_UPLOAD
    }

internal data class TripRegionOption(
    val code: String,
    val label: String,
)

internal val JEONBUK_REGIONS = listOf(
    TripRegionOption("JEONBUK", "전북 전체"),
    TripRegionOption("JEONJU", "전주시"),
    TripRegionOption("GUNSAN", "군산시"),
    TripRegionOption("IKSAN", "익산시"),
    TripRegionOption("JEONGEUP", "정읍시"),
    TripRegionOption("NAMWON", "남원시"),
    TripRegionOption("GIMJE", "김제시"),
    TripRegionOption("WANJU", "완주군"),
    TripRegionOption("JINAN", "진안군"),
    TripRegionOption("MUJU", "무주군"),
    TripRegionOption("JANGSU", "장수군"),
    TripRegionOption("IMSIL", "임실군"),
    TripRegionOption("SUNCHANG", "순창군"),
    TripRegionOption("GOCHANG", "고창군"),
    TripRegionOption("BUAN", "부안군"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewTripScreen(
    baseUrl: String,
    accessToken: String?,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    onCreated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = remember(baseUrl) { PlanningApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val derivatives = remember(context) { PhotoDerivativeStore(context) }
    val today = remember { LocalDate.now() }
    var title by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(today) }
    var endDate by remember { mutableStateOf(today) }
    var selectedRegion by remember { mutableStateOf(JEONBUK_REGIONS.first()) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var showDateSheet by remember { mutableStateOf(false) }
    var draftRange by remember { mutableStateOf(TripDateRange(start = today, end = today)) }
    var creating by remember { mutableStateOf(false) }
    var selectingCover by remember { mutableStateOf(false) }
    var selectedCoverFile by remember { mutableStateOf<File?>(null) }
    var preparedCover by remember { mutableStateOf<PreparedPhoto?>(null) }
    var createdTripId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val primaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = StogYellow,
        contentColor = StogInk,
        disabledContainerColor = StogBorder,
        disabledContentColor = StogMuted,
    )
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            selectingCover = true
            var newSource: File? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    derivatives.copyGallerySelection(uri).also { source ->
                        newSource = source
                        decodeTripCoverPreview(source.absolutePath)?.also { it.recycle() }
                            ?: throw IOException("선택한 사진을 읽을 수 없어요.")
                    }
                }
            }.onSuccess { source ->
                preparedCover?.let { old -> withContext(Dispatchers.IO) { derivatives.delete(old) } }
                selectedCoverFile = source
                preparedCover = null
                message = null
            }.onFailure {
                newSource?.delete()
                message = "대표 이미지를 선택하지 못했어요."
            }
            selectingCover = false
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = StogCanvas,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("새 여행") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_left),
                                contentDescription = "내 여행으로 돌아가기",
                                tint = StogInk,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = StogCanvas,
                        titleContentColor = StogInk,
                    ),
                )
                HorizontalDivider(color = StogBorder)
            }
        },
    ) { innerPadding ->
        if (accessToken.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StogStatePanel(
                    state = StogSurfaceState.AUTH_EXPIRED,
                    title = "로그인이 필요해요",
                    detail = "로그인하면 새 여행을 만들 수 있어요.",
                    action = StogSurfaceAction.NONE,
                )
                Button(
                    onClick = onLoginRequired,
                    modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                    colors = primaryButtonColors,
                ) {
                    Text("로그인")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .testTag("new_trip_scroll")
                    .imePadding()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "새로운 여행을 만들어보세요",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "여행 이름과 날짜만 입력하면 바로 시작할 수 있어요.",
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                item {
                    TripCoverPlaceholder(
                        imagePath = selectedCoverFile?.absolutePath,
                        loading = selectingCover,
                        onClick = {
                            if (!creating && !selectingCover) {
                                coverPicker.launch("image/*")
                            }
                        },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("여행 이름", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                if (it.isNotBlank()) titleError = null
                            },
                            modifier = Modifier.fillMaxWidth().stogTouchTarget(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            singleLine = true,
                            isError = titleError != null,
                            placeholder = { Text("예: 전주 가을 여행") },
                            supportingText = {
                                titleError?.let { error ->
                                    Text(error, color = StogWarm)
                                }
                            },
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("여행 날짜", style = MaterialTheme.typography.titleMedium)
                        TripDateRangeField(
                            startDate = startDate,
                            endDate = endDate,
                            onClick = {
                                draftRange = TripDateRange(start = startDate, end = endDate)
                                showDateSheet = true
                            },
                        )
                        Text(
                            tripNightsText(startDate, endDate),
                            modifier = Modifier.fillMaxWidth(),
                            color = StogMuted,
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("여행 지역", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "선택 지역: ${selectedRegion.label}",
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        JEONBUK_REGIONS.chunked(2).forEach { rowRegions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowRegions.forEach { region ->
                                    val selected = region == selectedRegion
                                    if (selected) {
                                        Button(
                                            onClick = { selectedRegion = region },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("new_trip_region_${region.code}")
                                                .stogTouchTarget(),
                                            colors = primaryButtonColors,
                                        ) {
                                            Text(region.label)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { selectedRegion = region },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("new_trip_region_${region.code}")
                                                .stogTouchTarget(),
                                            border = BorderStroke(1.dp, StogBorder),
                                        ) {
                                            Text(region.label)
                                        }
                                    }
                                }
                                if (rowRegions.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            titleError = tripTitleError(title)
                            if (titleError != null) return@Button
                            scope.launch {
                                creating = true
                                message = null
                                val tripId = runCatching {
                                    withContext(Dispatchers.IO) {
                                        createdTripId ?: client.createTrip(
                                                accessToken = accessToken,
                                                title = title.trim(),
                                                activityType = "tour",
                                                plannedStartDate = startDate.toString(),
                                                plannedEndDate = endDate.toString(),
                                                regionCode = selectedRegion.code,
                                            ).id
                                    }
                                }.getOrElse {
                                    message = "여행을 만들지 못했어요."
                                    creating = false
                                    return@launch
                                }
                                createdTripId = tripId
                                val source = selectedCoverFile
                                if (source != null) {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val prepared = preparedCover ?: derivatives.prepare(
                                                source = PhotoSource.GALLERY,
                                                tripId = tripId,
                                                sourceFile = source,
                                                coordinates = null,
                                                takenAt = null,
                                            ).also { preparedCover = it }
                                            client.uploadTripCover(
                                                accessToken,
                                                tripId,
                                                File(prepared.normalizedOriginalPath),
                                            )
                                            derivatives.delete(prepared)
                                        }
                                    }.onFailure {
                                        message = when (tripCreationFailureFor(createdTripId)) {
                                            TripCreationFailure.TRIP_CREATION ->
                                                "여행을 만들지 못했어요."
                                            TripCreationFailure.COVER_UPLOAD ->
                                                "여행은 만들어졌지만 대표 이미지를 반영하지 못했어요. 다시 시도해 주세요."
                                        }
                                        creating = false
                                        return@launch
                                    }
                                    preparedCover = null
                                    selectedCoverFile = null
                                }
                                onCreated()
                                creating = false
                            }
                        },
                        enabled = !creating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_trip_create")
                            .stogTouchTarget(),
                        colors = primaryButtonColors,
                    ) {
                        Text(if (creating) "여행 만드는 중" else "여행 만들기")
                    }
                }
                message?.let { text ->
                    item {
                        Text(text, color = StogMuted)
                    }
                }
            }
        }
    }

    if (showDateSheet) {
        TripDateRangeSheet(
            initialRange = draftRange,
            onDismiss = { showDateSheet = false },
            onComplete = { range ->
                startDate = requireNotNull(range.start)
                endDate = requireNotNull(range.end)
                showDateSheet = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

@Composable
private fun TripCoverPlaceholder(
    imagePath: String?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    var bitmap by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imagePath) {
        bitmap = withContext(Dispatchers.IO) {
            imagePath?.let(::decodeTripCoverPreview)
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (imagePath == null) {
                    "여행 대표 이미지. 선택하지 않으면 기본 이미지가 사용돼요."
                } else {
                    "여행 대표 이미지 변경"
                }
            },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .padding(14.dp)
                .drawBehind {
                    drawRoundRect(
                        color = StogBorder,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                            ),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = requireNotNull(bitmap).asImageBitmap(),
                    contentDescription = "선택한 여행 대표 이미지",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(StogBorder.copy(alpha = 0.24f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = null,
                            tint = StogMuted,
                            modifier = Modifier.size(32.dp),
                        )
                        Surface(
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = StogInk,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+", color = StogCanvas, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Text("여행 대표 이미지", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "선택하지 않으면 기본 이미지가 사용돼요",
                        color = StogMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = StogYellow,
                )
            }
        }
    }
}

private fun decodeTripCoverPreview(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return null

    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

@Composable
private fun TripDateRangeField(
    startDate: LocalDate,
    endDate: LocalDate,
    onClick: () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "여행 날짜 선택" },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = StogCanvas),
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(StogBorder.copy(alpha = 0.22f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_my_calendar),
                    contentDescription = null,
                    tint = StogInk,
                    modifier = Modifier.size(24.dp),
                )
            }
            DateValue(
                label = "시작 날짜",
                value = formatTripDate(startDate),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .width(1.dp)
                    .background(StogBorder),
            )
            DateValue(
                label = "종료 날짜",
                value = formatTripDate(endDate),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = StogMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DateValue(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = StogMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripDateRangeSheet(
    initialRange: TripDateRange,
    onDismiss: () -> Unit,
    onComplete: (TripDateRange) -> Unit,
    sheetState: SheetState,
) {
    var range by remember(initialRange) { mutableStateOf(initialRange) }
    var month by remember(initialRange) {
        mutableStateOf(YearMonth.from(initialRange.start ?: LocalDate.now()))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StogCanvas,
        scrimColor = StogInk.copy(alpha = 0.34f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 68.dp, height = 6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(StogBorder),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "여행 날짜 선택",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { month = month.minusMonths(1) },
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = "이전 달",
                        tint = StogInk,
                    )
                }
                Text(
                    "${month.year}년 ${month.monthValue}월",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(
                    onClick = { month = month.plusMonths(1) },
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "다음 달",
                        tint = StogInk,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEach { weekday ->
                    Text(
                        weekday,
                        modifier = Modifier.weight(1f),
                        color = StogMuted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Column {
                tripDatePickerDays(month).chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            val isStart = date == range.start
                            val isEnd = date == range.end
                            val isInRange = range.start?.let { start ->
                                range.end?.let { end ->
                                    !date.isBefore(start) && !date.isAfter(end)
                                }
                            } == true
                            val rangeShape = when {
                                isStart && isEnd -> RectangleShape
                                isStart -> androidx.compose.foundation.shape.RoundedCornerShape(
                                    topStart = 24.dp,
                                    bottomStart = 24.dp,
                                )
                                isEnd -> androidx.compose.foundation.shape.RoundedCornerShape(
                                    topEnd = 24.dp,
                                    bottomEnd = 24.dp,
                                )
                                else -> RectangleShape
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(
                                        if (isInRange && !(isStart && isEnd)) {
                                            StogYellow.copy(alpha = 0.22f)
                                        } else {
                                            Color.Transparent
                                        },
                                        rangeShape,
                                    )
                                    .clickable { range = selectTripDate(range, date) }
                                    .semantics {
                                        contentDescription = formatTripDate(date)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(
                                            if (isStart || isEnd) StogYellow else Color.Transparent,
                                            androidx.compose.foundation.shape.CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        color = if (YearMonth.from(date) == month) {
                                            StogInk
                                        } else {
                                            StogMuted.copy(alpha = 0.5f)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = StogCanvas,
                border = BorderStroke(1.dp, StogBorder),
            ) {
                if (range.isComplete) {
                    val start = requireNotNull(range.start)
                    val end = requireNotNull(range.end)
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${formatTripDate(start)} - ${formatTripDate(end)}")
                        Text(tripNightsText(start, end), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Text(
                        "날짜를 두 번 선택해주세요",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = StogMuted,
                    )
                }
            }
            Button(
                onClick = { onComplete(range) },
                enabled = range.isComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .stogTouchTarget(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StogYellow,
                    contentColor = StogInk,
                    disabledContainerColor = StogBorder,
                    disabledContentColor = StogMuted,
                ),
            ) {
                Text("선택 완료", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
