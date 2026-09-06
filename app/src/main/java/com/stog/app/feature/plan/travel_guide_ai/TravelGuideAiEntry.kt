package com.stog.app.feature.plan.travel_guide_ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import java.io.IOException
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

internal data class TravelGuideAiHost(
    val baseUrl: String,
    val accessToken: String?,
    val tripId: Long?,
    val tripTitle: String?,
)

@Composable
internal fun TravelGuideAiEntry(
    host: TravelGuideAiHost? = null,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<TravelGuideAiUiState>(TravelGuideAiUiState.Idle) }
    val scope = rememberCoroutineScope()
    val previewAvailable = when (val current = state) {
        is TravelGuideAiUiState.Error -> !current.authExpired
        is TravelGuideAiUiState.Applying,
        is TravelGuideAiUiState.Loading,
        -> false
        TravelGuideAiUiState.Idle,
        is TravelGuideAiUiState.Applied,
        is TravelGuideAiUiState.Preview,
        is TravelGuideAiUiState.Ready,
        -> true
    }
    LaunchedEffect(host?.tripId, host?.tripTitle) {
        state = TravelGuideAiState.selectTrip(state, host?.tripId, host?.tripTitle)
    }

    fun preview() {
        val token = host?.accessToken ?: return
        val tripId = state.tripId ?: return
        state = TravelGuideAiState.previewStarted(state)
        scope.launch {
            try {
                val proposal = withContext(Dispatchers.IO) {
                    TravelGuideAiApiClient(host.baseUrl).previewCurrentItinerary(token, tripId)
                }
                state = TravelGuideAiState.previewSucceeded(state, proposal)
            } catch (error: TravelGuideAiRequestException) {
                state = TravelGuideAiState.failed(state, error.authExpired, error.code)
            } catch (error: IOException) {
                state = TravelGuideAiState.failed(state, false, "NETWORK_UNAVAILABLE")
            } catch (error: JSONException) {
                state = TravelGuideAiState.failed(state, false, "PROPOSAL_RESPONSE_INVALID")
            } catch (error: DateTimeParseException) {
                state = TravelGuideAiState.failed(state, false, "PROPOSAL_RESPONSE_INVALID")
            }
        }
    }

    fun apply(
        proposal: TravelGuideAiProposal,
        clientApplyId: String = UUID.randomUUID().toString(),
    ) {
        val token = host?.accessToken ?: return
        state = TravelGuideAiState.applyStarted(state, clientApplyId)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TravelGuideAiApiClient(host.baseUrl).apply(token, proposal, clientApplyId)
                }
                state = TravelGuideAiState.applySucceeded(state, result)
            } catch (error: TravelGuideAiRequestException) {
                state = TravelGuideAiState.failed(state, error.authExpired, error.code)
            } catch (error: IOException) {
                state = TravelGuideAiState.failed(state, false, "NETWORK_UNAVAILABLE")
            } catch (error: JSONException) {
                state = TravelGuideAiState.failed(state, false, "PROPOSAL_RESPONSE_INVALID")
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag(host?.tripId?.let { "stobee_trip_$it" } ?: "stobee_trip_unselected")
            .imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("STOBEE", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = host?.tripTitle?.let { "$it 여행을 함께 정리해요." }
                ?: "여행을 선택하면 일정을 검토할 수 있어요.",
            style = MaterialTheme.typography.titleMedium,
            color = StogInk,
        )
        Text(
            text = if (host?.tripId != null) {
                "선택한 여행 기준 · 제안 미리보기 · 확인 후 적용"
            } else {
                "내 여행에서 작업할 여행을 먼저 선택해 주세요."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = StogMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("무엇을 도와드릴까요?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuideQuickAction("일정 검토", Modifier.weight(1f)) { message = "현재 일정이 가능한지 검토해줘" }
                GuideQuickAction("다음 장소", Modifier.weight(1f)) { message = "다음 장소를 알려줘" }
            }
        }
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("STOBEE에게 물어보세요") },
            minLines = 3,
        )
        Button(
            onClick = ::preview,
            enabled = host?.accessToken != null && state.tripId != null && previewAvailable,
            modifier = Modifier.fillMaxWidth().testTag("stobee_preview"),
        ) {
            Text("현재 일정 제안 미리보기")
        }
        when (val current = state) {
            TravelGuideAiUiState.Idle -> StogStatePanel(
                state = StogSurfaceState.EMPTY,
                title = "선택한 여행이 없어요",
                detail = "내 여행에서 작업할 여행을 선택해 주세요.",
            )
            is TravelGuideAiUiState.Ready -> Text(
                "대화만으로 일정은 변경되지 않아요.",
                color = StogMuted,
            )
            is TravelGuideAiUiState.Loading -> StogStatePanel(
                state = StogSurfaceState.LOADING,
                title = "일정을 검토하고 있어요",
                detail = "현재 일정은 그대로 유지됩니다.",
            )
            is TravelGuideAiUiState.Preview -> ProposalCard(current.proposal) { proposal ->
                apply(proposal)
            }
            is TravelGuideAiUiState.Applying -> StogStatePanel(
                state = StogSurfaceState.LOADING,
                title = "확인한 제안을 적용하고 있어요",
                detail = "완료 응답 전에는 반영된 것으로 표시하지 않습니다.",
            )
            is TravelGuideAiUiState.Error -> StogStatePanel(
                state = if (current.authExpired) StogSurfaceState.AUTH_EXPIRED else StogSurfaceState.ERROR,
                title = if (current.authExpired) "로그인이 만료되었어요" else "제안을 불러오지 못했어요",
                detail = current.message,
                action = if (current.authExpired) StogSurfaceAction.NONE else StogSurfaceAction.RETRY,
                actionLabel = if (current.authExpired) null else "다시 시도",
                onAction = {
                    when (current.retry) {
                        TravelGuideAiRetry.PREVIEW -> preview()
                        TravelGuideAiRetry.APPLY -> current.proposal?.let { proposal ->
                            apply(proposal, checkNotNull(current.clientApplyId))
                        }
                    }
                },
            )
            is TravelGuideAiUiState.Applied -> StogStatePanel(
                state = StogSurfaceState.CONTENT,
                title = "일정에 반영했어요",
                detail = "일정 버전 ${current.result.itineraryVersion}",
            )
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: TravelGuideAiProposal,
    onApply: (TravelGuideAiProposal) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("제안 미리보기", style = MaterialTheme.typography.titleMedium)
            Text("일정 항목 ${proposal.actions.size}개 · 고정 ${proposal.fixedBasketItemIds.size}개")
            if (proposal.excludedUnresolvedBasketItemIds.isNotEmpty()) {
                Text("위치 미확인 ${proposal.excludedUnresolvedBasketItemIds.size}개는 이동 계산에서 제외")
            }
            proposal.trailLegs.forEach { leg ->
                Text(
                    "${leg.travelMode} · ${leg.durationMinutes}분" +
                        (leg.distanceMeters?.let { " · ${it}m" } ?: ""),
                    color = StogMuted,
                )
            }
            proposal.violations.forEach { violation -> Text(violation.code, color = StogMuted) }
            Button(
                onClick = { onApply(proposal) },
                enabled = proposal.feasible,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("이 제안 적용")
            }
        }
    }
}

@Composable
private fun GuideQuickAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Text(label)
    }
}
