package com.stog.app.feature.record

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeLocationTuningTest {
    @Test
    fun homeRecommendationsUseTheirOwnCoarseLocationPolicy() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(500.0, context.homeRecommendationLocationTuning().maxAccuracyMeters, 0.0)
        assertEquals(60_000L, context.homeRecommendationLocationTuning().maxAgeMillis)
    }
}
