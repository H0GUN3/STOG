package com.stog.app.feature.profile

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreferenceSurveyHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialProfileLoadingShowsStobotCharacter() {
        composeRule.setContent {
            STOGTheme {
                InitialSurveyLoadingScreen(title = "STOG를 준비하고 있어요")
            }
        }

        composeRule.onNodeWithContentDescription("성향을 확인하는 STOBOT").assertExists()
    }

    @Test
    fun preferenceAnalysisLoadingShowsAnalysisMessage() {
        composeRule.setContent {
            STOGTheme {
                PreferenceAnalysisLoadingScreen()
            }
        }

        composeRule.onNodeWithText("성향을 분석하고 있어요").assertExists()
    }

    @Test
    fun advancingWithoutAnAnswerShowsValidation() {
        composeRule.setContent {
            STOGTheme {
                PreferenceSurveyScreen(
                    onSubmit = {},
                    submitting = false,
                    errorMessage = null,
                )
            }
        }

        composeRule.onNodeWithTag("survey_question_image_q1").assertExists()
        composeRule.onNodeWithText("다음").performClick()

        composeRule.onNodeWithText("답변을 선택해 주세요.").assertExists()
        composeRule.onNodeWithText("Q1").assertExists()
    }

    @Test
    fun selectingEveryAnswerSubmitsTheThirteenResponses() {
        var submittedAnswers: Map<String, Int>? = null
        composeRule.setContent {
            STOGTheme {
                PreferenceSurveyScreen(
                    onSubmit = { submittedAnswers = it },
                    submitting = false,
                    errorMessage = null,
                )
            }
        }

        repeat(PRECISION_SURVEY_QUESTIONS.size) { index ->
            composeRule.onNodeWithText("Q${index + 1}").assertExists()
            composeRule.onNodeWithTag("survey_scroll").performScrollToNode(
                hasTestTag("survey_option_${PRECISION_SURVEY_OPTIONS[2]}"),
            )
            composeRule.onNodeWithTag("survey_option_${PRECISION_SURVEY_OPTIONS[2]}").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(
                if (index == PRECISION_SURVEY_QUESTIONS.lastIndex) "완료" else "다음",
            ).performClick()
            composeRule.waitForIdle()
        }

        val answers = checkNotNull(submittedAnswers)
        assertEquals((1..13).associate { "q$it" to 3 }, answers)
        assertTrue(answers.keys.containsAll((1..13).map { "q$it" }))
    }
}
