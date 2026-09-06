package com.stog.app.feature.profile

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.stog.app.ui.theme.STOGTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StogCharacterHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun surveyResultShowsPrimaryCharacterAndFullAffinityFeed() {
        composeRule.setContent {
            STOGTheme {
                PreferenceSurveyResultScreen(
                    result = profileSurveyResult(
                        preferenceScores = mapOf(
                            "nature" to 0.25,
                            "culture" to 0.50,
                            "food" to 0.75,
                            "shopping" to 1.0,
                            "experience" to 0.0,
                            "relaxation" to 0.25,
                        ),
                    ),
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag("survey_result_primary").assertExists()
        composeRule.onNodeWithTag("survey_result_affinities").assertExists()
        composeRule.onNodeWithTag("character_affinity_shopping").assertExists()
        composeRule.onNodeWithTag("character_affinity_nature").assertExists()
    }

    @Test
    fun profileSectionUsesTheSameCharacterMapping() {
        composeRule.setContent {
            STOGTheme {
                ProfileCharacterSection(
                    scores = ProfileScores(
                        preferenceScores = mapOf(
                            "nature" to 0.25,
                            "culture" to 0.50,
                            "food" to 0.75,
                            "shopping" to 1.0,
                            "experience" to 0.0,
                            "relaxation" to 0.25,
                        ),
                        travelStyleScores = emptyMap(),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("profile_character").assertExists()
        composeRule.onNodeWithTag("profile_primary_character").assertExists()
        composeRule.onNodeWithTag("profile_character_affinities").assertExists()
        composeRule.onNodeWithTag("character_affinity_shopping").assertExists()
    }

    private fun profileSurveyResult(
        preferenceScores: Map<String, Double>,
    ) = ProfileSurveyResult(
        surveyVersion = "v1",
        surveyType = "precision",
        canonical = true,
        preferenceScores = preferenceScores,
        travelStyleScores = emptyMap(),
    )
}
