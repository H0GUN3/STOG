package com.stog.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogAssetImage
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow

internal enum class InitialProfileState {
    IDLE,
    CHECKING,
    REQUIRED,
    RESULT,
    COMPLETE,
    ERROR,
}

internal data class PreferenceSurveyQuestion(
    val answerKey: String,
    val text: String,
    val imageAsset: String,
)

internal val PRECISION_SURVEY_OPTIONS = listOf(
    "전혀 아니다",
    "조금 아니다",
    "보통이다",
    "그런 편이다",
    "매우 그렇다",
)

internal val PRECISION_SURVEY_QUESTIONS = listOf(
    PreferenceSurveyQuestion(
        "q1",
        "숲·공원·바다처럼 자연을 느낄 수 있는 장소를 선호하나요?",
        "question_q1.png",
    ),
    PreferenceSurveyQuestion(
        "q2",
        "박물관·문화유산·전통거리처럼 역사와 문화를 느낄 수 있는 장소를 좋아하나요?",
        "question_q2.png",
    ),
    PreferenceSurveyQuestion(
        "q3",
        "여행에서 맛집이나 카페를 찾아가는 것이 중요한 편인가요?",
        "question_q3.png",
    ),
    PreferenceSurveyQuestion(
        "q4",
        "쇼핑이나 기념품·지역 상품을 구경하고 구매하는 것을 즐기나요?",
        "question_q4.png",
    ),
    PreferenceSurveyQuestion(
        "q5",
        "공연·축제·액티비티·체험 프로그램처럼 직접 경험하는 활동을 즐기나요?",
        "question_q5.png",
    ),
    PreferenceSurveyQuestion(
        "q6",
        "여행 중 충분히 쉬거나 풍경을 천천히 즐기는 시간을 중요하게 생각하나요?",
        "question_q6.png",
    ),
    PreferenceSurveyQuestion(
        "q7",
        "유명하고 대표적인 관광지를 우선해서 방문하는 편인가요?",
        "question_q7.png",
    ),
    PreferenceSurveyQuestion(
        "q8",
        "사람이 많더라도 가고 싶은 인기 장소라면 방문하는 편인가요?",
        "question_q8.png",
    ),
    PreferenceSurveyQuestion(
        "q9",
        "하루에 여러 장소를 둘러보는 알찬 일정을 선호하나요?",
        "question_q9.png",
    ),
    PreferenceSurveyQuestion(
        "q10",
        "여행 중에도 미리 정한 계획을 가능한 그대로 따르는 편인가요?",
        "question_q10.png",
    ),
    PreferenceSurveyQuestion(
        "q11",
        "걷기·등산·자전거·액티비티처럼 몸을 사용하는 여행 활동을 즐기나요?",
        "question_q11.png",
    ),
    PreferenceSurveyQuestion(
        "q12",
        "잘 알려지고 익숙한 곳보다 처음 접하는 독특하고 새로운 장소를 찾아가고 싶나요?",
        "question_q12.png",
    ),
    PreferenceSurveyQuestion(
        "q13",
        "정말 가고 싶은 장소라면 이동 시간이 길거나 많이 걸어야 해도 방문할 의향이 있나요?",
        "question_q13.png",
    ),
)

private val PREFERENCE_KEYS = setOf(
    "nature",
    "culture",
    "food",
    "shopping",
    "experience",
    "relaxation",
)

private val TRAVEL_STYLE_KEYS = setOf(
    "localness",
    "crowd_tolerance",
    "pace",
    "spontaneity",
    "activity_intensity",
    "novelty_seeking",
    "travel_effort_tolerance",
)

internal fun needsInitialSurvey(
    preferenceScores: Map<String, Double>,
    travelStyleScores: Map<String, Double>,
): Boolean =
    !PREFERENCE_KEYS.all { it in preferenceScores } ||
        !TRAVEL_STYLE_KEYS.all { it in travelStyleScores }

private val surveyAnswersSaver: Saver<Map<String, Int>, Any> = listSaver(
    save = { answers ->
        answers.entries.flatMap { listOf(it.key, it.value.toString()) }
    },
    restore = { values ->
        values.chunked(2).associate { (key, value) -> key to value.toInt() }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreferenceSurveyScreen(
    onSubmit: (Map<String, Int>) -> Unit,
    submitting: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    var currentIndex by rememberSaveable { mutableStateOf(0) }
    var answers by rememberSaveable(stateSaver = surveyAnswersSaver) {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val question = PRECISION_SURVEY_QUESTIONS[currentIndex]
    val selectedAnswer = answers[question.answerKey]
    val isLastQuestion = currentIndex == PRECISION_SURVEY_QUESTIONS.lastIndex
    val visibleError = validationMessage ?: errorMessage

    BackHandler(enabled = currentIndex > 0 && !submitting) {
        currentIndex--
        validationMessage = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("여행 성향 알아보기") },
                navigationIcon = {
                    if (currentIndex > 0 && !submitting) {
                        androidx.compose.material3.IconButton(
                            onClick = {
                                currentIndex--
                                validationMessage = null
                            },
                            modifier = Modifier
                                .stogTouchTarget()
                                .semantics { contentDescription = "이전 질문" },
                        ) {
                            Text("이전", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = StogCanvas,
                border = BorderStroke(1.dp, StogBorder),
            ) {
                Button(
                    onClick = {
                        if (selectedAnswer == null) {
                            validationMessage = "답변을 선택해 주세요."
                        } else if (isLastQuestion) {
                            onSubmit(answers)
                        } else {
                            currentIndex++
                            validationMessage = null
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = StogUiContract.ScreenGutterDp.dp,
                            vertical = StogUiContract.BaseSpacingDp.dp * 2,
                        )
                        .heightIn(min = StogUiContract.MinTouchTargetDp.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StogYellow,
                        contentColor = StogInk,
                    ),
                    shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = StogInk,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "저장 중",
                            modifier = Modifier.padding(start = StogUiContract.BaseSpacingDp.dp),
                        )
                    } else {
                        Text(if (isLastQuestion) "완료" else "다음")
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("survey_scroll")
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = StogUiContract.ScreenGutterDp.dp,
                vertical = StogUiContract.ScreenGutterDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp)) {
                    Text(
                        "${currentIndex + 1} / ${PRECISION_SURVEY_QUESTIONS.size}",
                        color = StogMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (currentIndex + 1).toFloat() / PRECISION_SURVEY_QUESTIONS.size
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = StogYellow,
                        trackColor = StogBorder,
                    )
                    Text(
                        "답변을 바탕으로 나에게 맞는 여행을 준비해 드릴게요.",
                        color = StogMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            visibleError?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = StogBorder,
                            shape = RoundedCornerShape(StogUiContract.MediumRadiusDp.dp),
                        )
                        .padding(StogUiContract.BaseSpacingDp.dp * 2),
                    verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
                ) {
                    Text(
                        "Q${currentIndex + 1}",
                        color = StogMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    StogAssetImage(
                        assetPath = question.imageAsset,
                        contentDescription = "${question.text} 관련 이미지",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(StogUiContract.MediumRadiusDp.dp))
                            .testTag("survey_question_image_${question.answerKey}"),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        question.text,
                        color = StogInk,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { selectableGroup() },
                        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
                    ) {
                        PRECISION_SURVEY_OPTIONS.forEach { option ->
                            val optionIndex = PRECISION_SURVEY_OPTIONS.indexOf(option) + 1
                            SurveyOptionRow(
                                label = option,
                                selected = selectedAnswer == optionIndex,
                                enabled = !submitting,
                                onClick = {
                                    answers = answers + (question.answerKey to optionIndex)
                                    validationMessage = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurveyOptionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(StogUiContract.SmallRadiusDp.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("survey_option_$label")
            .heightIn(min = StogUiContract.MinTouchTargetDp.dp)
            .background(
                color = if (selected) StogYellow.copy(alpha = 0.22f) else StogSurface,
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = if (selected) StogInk else StogBorder,
                shape = shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = StogUiContract.BaseSpacingDp.dp * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Text(
            label,
            color = StogInk,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = StogUiContract.BaseSpacingDp.dp),
        )
    }
}

@Composable
internal fun InitialSurveyLoadingScreen(
    title: String,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(StogUiContract.ScreenGutterDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    StogUiContract.BaseSpacingDp.dp * 2,
                ),
            ) {
                StogAssetImage(
                    assetPath = "STOBOT.png",
                    contentDescription = "성향을 확인하는 STOBOT",
                    modifier = Modifier.size(180.dp),
                    maxDimensionPx = 512,
                )
                StogStatePanel(
                    state = StogSurfaceState.LOADING,
                    title = title,
                    detail = "잠시만 기다려 주세요.",
                )
            }
        }
    }
}

@Composable
internal fun PreferenceAnalysisLoadingScreen(
    modifier: Modifier = Modifier,
) {
    InitialSurveyLoadingScreen(
        title = "성향을 분석하고 있어요",
        modifier = modifier,
    )
}

@Composable
internal fun InitialSurveyErrorScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(StogUiContract.ScreenGutterDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            StogStatePanel(
                state = StogSurfaceState.ERROR,
                title = "여행 성향을 확인하지 못했어요",
                detail = "인터넷 연결을 확인한 뒤 다시 시도해 주세요.",
                action = StogSurfaceAction.RETRY,
                actionLabel = "다시 시도",
                onAction = onRetry,
            )
        }
    }
}
