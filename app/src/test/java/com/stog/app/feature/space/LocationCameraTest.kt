package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCameraTest {
    @Test
    fun permissionRefreshCreatesANewLocationCameraRequest() {
        assertEquals(3, nextLocationCameraRequestKey(permissionGranted = true, currentKey = 2))
        assertEquals(2, nextLocationCameraRequestKey(permissionGranted = false, currentKey = 2))
    }

    @Test
    fun recentersOnlyWhenAResumeRequestHasNotBeenHandled() {
        assertTrue(needsLocationCameraRecenter(requestKey = 2, lastCenteredRequestKey = 1))
        assertFalse(needsLocationCameraRecenter(requestKey = 2, lastCenteredRequestKey = 2))
    }
}
