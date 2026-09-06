package com.stog.app

import com.stog.app.navigation.AppDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test
    fun homeStartsOnDashboard() {
        assertFalse(HOME_STARTS_WITH_MAP)
    }

    @Test
    fun captureUsesDarkSystemBarsAndLeavingCaptureRestoresDestinationAppearance() {
        val capture = appSystemBarAppearance(AppDestination.CAPTURE, showingMap = false)
        assertFalse(capture.lightStatusBars)
        assertFalse(capture.lightNavigationBars)

        val home = appSystemBarAppearance(AppDestination.HOME, showingMap = false)
        assertTrue(home.lightStatusBars)
        assertTrue(home.lightNavigationBars)
    }

    @Test
    fun surveyUsesLightSystemBars() {
        val survey = appSystemBarAppearance(AppDestination.SURVEY, showingMap = false)

        assertTrue(survey.lightStatusBars)
        assertTrue(survey.lightNavigationBars)
    }
}
