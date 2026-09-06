package com.stog.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRationaleTest {
    @Test
    fun firstRequestShowsAnExplanationBeforeTheSystemPrompt() {
        assertEquals(
            PermissionRequestDecision.SHOW_RATIONALE,
            decidePermissionRequest(
                granted = false,
                shouldShowRationale = false,
                requestAttempted = false,
            ),
        )
    }

    @Test
    fun deniedPermissionUsesAndroidRationaleWhenAvailable() {
        assertEquals(
            PermissionRequestDecision.SHOW_RATIONALE,
            decidePermissionRequest(
                granted = false,
                shouldShowRationale = true,
                requestAttempted = true,
            ),
        )
    }

    @Test
    fun repeatedDeniedPermissionOpensAppSettings() {
        assertEquals(
            PermissionRequestDecision.OPEN_SETTINGS,
            decidePermissionRequest(
                granted = false,
                shouldShowRationale = false,
                requestAttempted = true,
            ),
        )
    }

    @Test
    fun grantedPermissionDoesNotPrompt() {
        assertEquals(
            PermissionRequestDecision.ALREADY_GRANTED,
            decidePermissionRequest(
                granted = true,
                shouldShowRationale = false,
                requestAttempted = true,
            ),
        )
    }
}
