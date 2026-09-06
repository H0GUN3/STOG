package com.stog.app.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterAffinityTest {
    @Test
    fun highestPreferenceScoreSelectsTheMatchingCharacter() {
        val scores = mapOf(
            "nature" to 0.25,
            "culture" to 0.50,
            "food" to 0.75,
            "shopping" to 1.0,
            "experience" to 0.0,
            "relaxation" to 0.25,
        )

        assertEquals(StogCharacter.PICK, characterAffinities(scores).first().character)
    }

    @Test
    fun equalScoresKeepTheBrandOrder() {
        val scores = StogCharacter.entries.associate { it.scoreKey to 0.5 }

        assertEquals(
            StogCharacter.entries.toList(),
            characterAffinities(scores).map(CharacterAffinity::character),
        )
    }
}
