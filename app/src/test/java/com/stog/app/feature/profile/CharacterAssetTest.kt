package com.stog.app.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAssetTest {
    @Test
    fun characterAndQuestionImagesUseTheBundledPngContract() {
        val paths = StogCharacter.entries.map { it.assetName } +
            PRECISION_SURVEY_QUESTIONS.map { it.imageAsset }

        assertEquals(6, StogCharacter.entries.size)
        assertEquals(13, PRECISION_SURVEY_QUESTIONS.size)
        assertTrue(paths.all { it.endsWith(".png") })
    }
}
