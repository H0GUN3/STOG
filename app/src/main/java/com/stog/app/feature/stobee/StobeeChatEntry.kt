package com.stog.app.feature.stobee

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiApiClient
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiProposal
import com.stog.app.feature.plan.travel_guide_ai.TravelGuideAiRequestException
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.feature.space.SheetLevel
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.StogAssetImage
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class StobeeChatMessage(
    val isUser: Boolean,
    val text: String,
)

internal data class StobeeChatResponse(
    val sessionId: String,
    val message: String,
    val recommendations: List<StobeePlaceRecommendation> = emptyList(),
)

internal data class StobeePlaceRecommendation(
    val placeId: Long?,
    val provider: String,
    val externalId: String,
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val types: List<String>,
    val sourceType: String?,
    val sourceId: Long?,
    val catalogStatus: String?,
)

internal class StobeeChatRequestException(
    val statusCode: Int,
    val code: String,
) : IOException(code)

internal class StobeeChatApiClient(
    private val baseUrl: String,
) {
    fun chat(
        accessToken: String,
        sessionId: String,
        message: String,
    ): StobeeChatResponse {
        val connection = URL("${baseUrl.trimEnd('/')}/stobee/chat")
            .openConnection() as HttpURLConnection
        try {
            val body = JSONObject()
                .put("session_id", sessionId)
                .put("message", message)
                .toString()
            connection.requestMethod = "POST"
            connection.connectTimeout = CHAT_TIMEOUT_MILLIS
            connection.readTimeout = CHAT_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                val code = runCatching {
                    JSONObject(responseBody).optString("detail")
                }.getOrNull()?.takeIf(String::isNotBlank) ?: "STOBEE_CHAT_FAILED"
                throw StobeeChatRequestException(statusCode, code)
            }
            return parseStobeeChatResponse(responseBody, sessionId)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseStobeeChatResponse(
    responseBody: String,
    fallbackSessionId: String,
): StobeeChatResponse {
    val response = JSONObject(responseBody)
    val message = response.optString("message", "")
        .trim()
        .takeUnless { it.equals("null", ignoreCase = true) }
        .orEmpty()
    if (message.isBlank()) {
        throw IOException("STOBEE_CHAT_RESPONSE_INVALID")
    }
    return StobeeChatResponse(
        sessionId = response.optString("session_id", fallbackSessionId),
        message = message,
        recommendations = response.optJSONArray("recommendations")
            ?.let { recommendations ->
                List(recommendations.length()) { index ->
                    val item = recommendations.getJSONObject(index)
                    StobeePlaceRecommendation(
                        placeId = item.optLong("place_id").takeIf {
                            item.has("place_id") && !item.isNull("place_id")
                        },
                        provider = item.optString("provider"),
                        externalId = item.optString("external_id"),
                        name = item.optString("name"),
                        address = item.optString("formatted_address")
                            .takeIf(String::isNotBlank),
                        latitude = item.optDouble("latitude").takeUnless(Double::isNaN),
                        longitude = item.optDouble("longitude").takeUnless(Double::isNaN),
                        types = item.optJSONArray("types")?.let { types ->
                            List(types.length()) { typeIndex -> types.getString(typeIndex) }
                        }.orEmpty(),
                        sourceType = item.optString("source_type")
                            .takeIf(String::isNotBlank),
                        sourceId = item.optLong("source_id").takeIf {
                            item.has("source_id") && !item.isNull("source_id")
                        },
                        catalogStatus = item.optString("catalog_status")
                            .takeIf(String::isNotBlank),
                    )
                }
            }.orEmpty(),
    )
}

@Composable
internal fun StobeeChatEntry(
    baseUrl: String,
    accessToken: String?,
    level: SheetLevel,
    tripId: Long?,
    tripTitle: String? = null,
    onActivate: () -> Unit,
    onLoginRequired: () -> Unit,
    onSelectTrip: (Long, String) -> Unit = { _, _ -> },
    tripLoader: (suspend (String) -> List<TripSummary>)? = null,
    modifier: Modifier = Modifier,
) {
    val client = remember(baseUrl) { StobeeChatApiClient(baseUrl) }
    val itineraryClient = remember(baseUrl) { TravelGuideAiApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var sessionId by rememberSaveable {
        mutableStateOf("stobee-${UUID.randomUUID()}")
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                StobeeChatMessage(
                    isUser = false,
                    text = "안녕하세요. 무엇을 도와드릴까요?",
                ),
            ),
        )
    }
    var sending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingProposal by remember { mutableStateOf<TravelGuideAiProposal?>(null) }
    var recommendations by remember { mutableStateOf<List<StobeePlaceRecommendation>>(emptyList()) }
    var selectedRecommendationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTripId by rememberSaveable { mutableStateOf<Long?>(null) }
    var availableTrips by remember { mutableStateOf<List<TripSummary>>(emptyList()) }
    var loadingTrips by remember { mutableStateOf(false) }
    var tripLoadMessage by remember { mutableStateOf<String?>(null) }
    val activeTripId = tripId ?: selectedTripId
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val loadTrips: suspend (String) -> List<TripSummary> =
        tripLoader ?: { token: String -> PlanningApiClient(baseUrl).listTrips(token) }

    LaunchedEffect(accessToken, tripId, level) {
        val token = accessToken?.takeIf(String::isNotBlank)
        if (token == null ||
            tripId != null ||
            selectedTripId != null ||
            level == SheetLevel.Collapsed
        ) {
            return@LaunchedEffect
        }
        loadingTrips = true
        tripLoadMessage = null
        runCatching {
            withContext(Dispatchers.IO) {
                loadTrips(token)
            }
        }.onSuccess { trips ->
            availableTrips = trips
        }.onFailure {
            tripLoadMessage = "여행 목록을 불러오지 못했어요."
        }
        loadingTrips = false
    }

    LaunchedEffect(level) {
        if (level != SheetLevel.Collapsed) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus(force = false)
        }
    }
    LaunchedEffect(messages.size, recommendations.isNotEmpty(), sending, level) {
        if (level != SheetLevel.Collapsed && messages.isNotEmpty()) {
            listState.animateScrollToItem(
                stobeeChatLastItemIndex(
                    messageCount = messages.size,
                    hasRecommendations = recommendations.isNotEmpty(),
                    sending = sending,
                ),
            )
        }
    }

    fun submit(input: String) {
        val message = input.trim()
        if (message.isBlank() || sending) return
        if (accessToken.isNullOrBlank()) {
            onLoginRequired()
            return
        }

        messages = messages + StobeeChatMessage(isUser = true, text = message)
        draft = ""
        errorMessage = null
        sending = true
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    client.chat(accessToken, sessionId, message)
                }
                sessionId = response.sessionId
                messages = messages + StobeeChatMessage(
                    isUser = false,
                    text = response.message,
                )
                recommendations = response.recommendations
                selectedRecommendationIds = emptySet()
                if (activeTripId != null && isItineraryRequest(message)) {
                    pendingProposal = withContext(Dispatchers.IO) {
                        itineraryClient.previewBasketItinerary(accessToken, activeTripId)
                    }
                    messages = messages + StobeeChatMessage(
                        isUser = false,
                        text = "바구니 장소를 포함한 동선 제안을 만들었어요. 확인 후 적용할 수 있어요.",
                    )
                }
            } catch (error: StobeeChatRequestException) {
                if (error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    onLoginRequired()
                } else {
                    errorMessage = "STOBEE 연결을 잠시 후 다시 시도해 주세요."
                    Log.w(TAG, "chat request failed: ${error.code}", error)
                }
            } catch (error: IOException) {
                errorMessage = "STOBEE 연결을 잠시 후 다시 시도해 주세요."
                Log.w(TAG, "chat request failed", error)
            } finally {
                sending = false
            }
        }
    }

    fun send() {
        submit(draft)
    }

    fun addRecommendation(recommendation: StobeePlaceRecommendation) {
        val token = accessToken
        val trip = activeTripId
        if (token.isNullOrBlank()) {
            onLoginRequired()
            return
        }
        if (trip == null) {
            messages = messages + StobeeChatMessage(
                isUser = false,
                text = "장소를 담을 여행을 먼저 선택해 주세요.",
            )
            return
        }
        if (recommendation.externalId in selectedRecommendationIds || sending) return
        sending = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PlanningApiClient(baseUrl).addToBasket(
                        token,
                        trip,
                        recommendation.toPlaceSearchCandidate(),
                    )
                }
            }.onSuccess {
                selectedRecommendationIds =
                    selectedRecommendationIds + recommendation.externalId
                messages = messages + StobeeChatMessage(
                    isUser = false,
                    text = "${recommendation.name}을(를) 바구니에 담았어요.",
                )
            }.onFailure {
                errorMessage = "추천 장소를 바구니에 담지 못했어요."
            }
            sending = false
        }
    }

    fun applyProposal(proposal: TravelGuideAiProposal) {
        val token = accessToken ?: return onLoginRequired()
        if (sending) return
        sending = true
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    itineraryClient.apply(token, proposal)
                }
                pendingProposal = null
                messages = messages + StobeeChatMessage(
                    isUser = false,
                    text = "일정과 동선 제안을 적용했어요.",
                )
            } catch (error: TravelGuideAiRequestException) {
                if (error.authExpired) {
                    onLoginRequired()
                } else {
                    errorMessage = "일정 제안을 적용하지 못했어요."
                    Log.w(TAG, "itinerary apply failed: ${error.code}", error)
                }
            } finally {
                sending = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize(),
    ) {
        if (level == SheetLevel.Collapsed) {
            ChatCollapsedBar(onActivate = onActivate)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                }
                if (recommendations.isNotEmpty()) {
                    item {
                        StobeeRecommendationCards(
                            recommendations = recommendations,
                            selectedIds = selectedRecommendationIds,
                            hasTrip = activeTripId != null,
                            onSelect = ::addRecommendation,
                        )
                    }
                }
                if (sending) {
                    item {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (activeTripId == null) {
                StobeeTripPicker(
                    trips = availableTrips,
                    loading = loadingTrips,
                    message = tripLoadMessage,
                    onSelect = { trip ->
                        selectedTripId = trip.id
                        availableTrips = emptyList()
                        onSelectTrip(trip.id, trip.title)
                    },
                )
            }
            StobeeQuickChoices(onSelect = ::submit)
            pendingProposal?.let { proposal ->
                IconButton(
                    onClick = { applyProposal(proposal) },
                    enabled = !sending,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 24.dp)
                        .stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_social_send),
                        contentDescription = "일정 동선 적용",
                        tint = StogInk,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stobee_input_bar")
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = if (imeVisible) 0.dp else 16.dp,
                    ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .height(56.dp),
                    placeholder = { Text("STOBEE에게 말해 보세요") },
                    singleLine = true,
                    enabled = !sending,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StogBorder,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                IconButton(
                    onClick = ::send,
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_social_send),
                        contentDescription = "STOBEE 보내기",
                        tint = if (draft.isNotBlank()) StogInk else StogMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StobeeTripPicker(
    trips: List<TripSummary>,
    loading: Boolean,
    message: String?,
    onSelect: (TripSummary) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "일정 추천을 받을 여행을 선택해 주세요.",
            color = StogInk,
            style = MaterialTheme.typography.labelLarge,
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        message?.let {
            Text(
                text = it,
                color = StogMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        trips.forEach { trip ->
            OutlinedButton(
                onClick = { onSelect(trip) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stobee_trip_option_${trip.id}"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(trip.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!loading && message == null && trips.isEmpty()) {
            Text(
                text = "먼저 여행을 만들어 주세요.",
                color = StogMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StobeeQuickChoices(
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "카페 위주" to "카페와 휴식 위주로 추천해줘",
            "전시·문화" to "전시와 문화 장소를 추천해줘",
            "자연·산책" to "자연과 산책 장소를 추천해줘",
        ).forEach { (label, prompt) ->
            OutlinedButton(
                onClick = { onSelect(prompt) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("stobee_choice_$label"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StobeeRecommendationCards(
    recommendations: List<StobeePlaceRecommendation>,
    selectedIds: Set<String>,
    hasTrip: Boolean,
    onSelect: (StobeePlaceRecommendation) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "STOBEE 추천 장소 ${recommendations.take(5).size}곳",
            style = MaterialTheme.typography.titleSmall,
            color = StogInk,
        )
        recommendations.take(5).forEach { recommendation ->
            val selected = recommendation.externalId in selectedIds
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StogBorder),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            recommendation.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        recommendation.address?.let {
                            Text(
                                it,
                                color = StogMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { onSelect(recommendation) },
                        enabled = !selected && hasTrip,
                        modifier = Modifier.stogTouchTarget(),
                    ) {
                        Text(if (selected) "담김" else "바구니")
                    }
                }
            }
        }
    }
}

internal fun StobeePlaceRecommendation.toPlaceSearchCandidate(): PlaceSearchCandidate =
    PlaceSearchCandidate(
        externalId = externalId,
        name = name,
        address = address,
        latitude = latitude,
        longitude = longitude,
        types = types,
        photoUrls = emptyList(),
        provenance = if (placeId != null && sourceId != null && sourceType != null) {
            PlaceSearchProvenance.Canonical(
                placeId = placeId,
                sourceType = sourceType,
                sourceId = sourceId,
                catalogStatus = catalogStatus.orEmpty(),
            )
        } else {
            PlaceSearchProvenance.GoogleFallback
        },
    )

@Composable
private fun ChatCollapsedBar(
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate)
            .semantics {
                contentDescription = "STOBEE 채팅 열기"
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "STOBEE",
                style = MaterialTheme.typography.labelMedium,
                color = StogMuted,
            )
            Text(
                text = "여행 이야기를 시작해 보세요",
                style = MaterialTheme.typography.bodyMedium,
                color = StogInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "열기",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChatBubble(
    message: StobeeChatMessage,
) {
    if (message.isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            StobeeMessageCard(message)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = StobeeProfileShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, StogYellow),
            ) {
                StogAssetImage(
                    assetPath = "STOBOT1.png",
                    contentDescription = "STOBOT 프로필",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(StobeeProfileShape),
                )
            }
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "스토비",
                    color = StogInk,
                    style = MaterialTheme.typography.titleSmall,
                )
                StobeeMessageCard(message)
            }
        }
    }
}

@Composable
private fun StobeeMessageCard(
    message: StobeeChatMessage,
) {
    Surface(
        color = if (message.isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            StogYellow
        },
        contentColor = if (message.isUser) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            StogInk
        },
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Text(
            text = message.text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val StobeeProfileShape = GenericShape { size, _ ->
    moveTo(size.width * 0.25f, 0f)
    lineTo(size.width * 0.75f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.75f, size.height)
    lineTo(size.width * 0.25f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

private const val CHAT_TIMEOUT_MILLIS = 30_000
private const val TAG = "StobeeChat"

internal fun isItineraryRequest(message: String): Boolean =
    listOf("일정", "동선", "순서", "여행 계획", "재배치", "추천", "짜줘", "계획").any(
        message::contains,
    )

internal fun stobeeChatLastItemIndex(
    messageCount: Int,
    hasRecommendations: Boolean,
    sending: Boolean,
): Int = (messageCount - 1 +
    (if (hasRecommendations) 1 else 0) +
    (if (sending) 1 else 0)).coerceAtLeast(0)
