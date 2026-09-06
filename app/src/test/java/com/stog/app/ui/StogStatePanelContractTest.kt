package com.stog.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StogStatePanelContractTest {
    @Test
    fun everyJudgeFacingStateHasDeterministicActionAvailability() {
        val expected = mapOf(
            StogSurfaceState.LOADING to StogSurfaceAction.NONE,
            StogSurfaceState.EMPTY to StogSurfaceAction.NONE,
            StogSurfaceState.ERROR to StogSurfaceAction.RETRY,
            StogSurfaceState.OFFLINE to StogSurfaceAction.RETRY,
            StogSurfaceState.AUTH_EXPIRED to StogSurfaceAction.LOGIN,
            StogSurfaceState.PERMISSION_DENIED to StogSurfaceAction.REQUEST_PERMISSION,
            StogSurfaceState.CONTENT to StogSurfaceAction.NONE,
        )

        assertEquals(StogSurfaceState.entries.toSet(), expected.keys)
        expected.forEach { (state, action) ->
            assertEquals(action, defaultActionFor(state))
            assertEquals(action != StogSurfaceAction.NONE, state.isRecoverable)
            assertTrue(state.machineName.isNotBlank())
        }
    }

    @Test
    fun onlyFailureStatesAnnounceAsLiveStatus() {
        assertFalse(StogSurfaceState.CONTENT.announcesChanges)
        assertFalse(StogSurfaceState.EMPTY.announcesChanges)
        assertTrue(StogSurfaceState.LOADING.announcesChanges)
        assertTrue(StogSurfaceState.ERROR.announcesChanges)
        assertTrue(StogSurfaceState.OFFLINE.announcesChanges)
        assertTrue(StogSurfaceState.AUTH_EXPIRED.announcesChanges)
        assertTrue(StogSurfaceState.PERMISSION_DENIED.announcesChanges)
    }
}
