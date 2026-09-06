package com.stog.app.feature.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSurveyStateTest {
    @Test
    fun profileNeedsSurveyUntilBothScoreLayersAreComplete() {
        assertTrue(needsInitialSurvey(emptyMap(), emptyMap()))
        assertTrue(
            needsInitialSurvey(
                preferenceScores = mapOf("nature" to 0.5),
                travelStyleScores = completeTravelStyleScores(),
            ),
        )
        assertFalse(
            needsInitialSurvey(
                preferenceScores = completePreferenceScores(),
                travelStyleScores = completeTravelStyleScores(),
            ),
        )
    }

    @Test
    fun precisionQuestionsMatchTheCanonicalThirteenKeys() {
        assertEquals(13, PRECISION_SURVEY_QUESTIONS.size)
        assertEquals(
            (1..13).map { "q$it" },
            PRECISION_SURVEY_QUESTIONS.map(PreferenceSurveyQuestion::answerKey),
        )
        assertEquals(5, PRECISION_SURVEY_OPTIONS.size)
    }

    private fun completePreferenceScores(): Map<String, Double> = mapOf(
        "nature" to 0.5,
        "culture" to 0.5,
        "food" to 0.5,
        "shopping" to 0.5,
        "experience" to 0.5,
        "relaxation" to 0.5,
    )

    private fun completeTravelStyleScores(): Map<String, Double> = mapOf(
        "localness" to 0.5,
        "crowd_tolerance" to 0.5,
        "pace" to 0.5,
        "spontaneity" to 0.5,
        "activity_intensity" to 0.5,
        "novelty_seeking" to 0.5,
        "travel_effort_tolerance" to 0.5,
    )
}
