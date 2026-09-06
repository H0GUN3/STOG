package com.stog.app.feature.space

import org.junit.Assert.assertTrue
import org.junit.Test

class ItineraryDetailScreenTest {
    @Test
    fun stobeeGenerationIsEnabledForAnAuthenticatedEmptyBasket() {
        assertTrue(
            canCreateStobeeItinerary(
                accessToken = "token",
                saving = false,
            ),
        )
    }
}
