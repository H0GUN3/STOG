package com.stog.app.feature.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetLogPermissionFlowTest {
    @Test
    fun entryPermissionFlowRequestsCameraLocationThenSettingsBeforeReady() {
        assertEquals(
            SetLogEntryPermissionStep.REQUEST_CAMERA,
            setLogEntryPermissionStep(
                cameraPermissionGranted = false,
                locationPermissionGranted = false,
                locationProviderEnabled = false,
            ),
        )
        assertEquals(
            SetLogEntryPermissionStep.REQUEST_LOCATION,
            setLogEntryPermissionStep(
                cameraPermissionGranted = true,
                locationPermissionGranted = false,
                locationProviderEnabled = false,
            ),
        )
        assertEquals(
            SetLogEntryPermissionStep.OPEN_LOCATION_SETTINGS,
            setLogEntryPermissionStep(
                cameraPermissionGranted = true,
                locationPermissionGranted = true,
                locationProviderEnabled = false,
            ),
        )
        assertEquals(
            SetLogEntryPermissionStep.READY,
            setLogEntryPermissionStep(
                cameraPermissionGranted = true,
                locationPermissionGranted = true,
                locationProviderEnabled = true,
            ),
        )
    }

    @Test
    fun coarsePermissionUsesOnlyNetworkProviderForEntrySettings() {
        assertFalse(
            setLogLocationProviderEnabled(
                finePermissionGranted = false,
                coarsePermissionGranted = true,
                gpsProviderEnabled = true,
                networkProviderEnabled = false,
            ),
        )
        assertTrue(
            setLogLocationProviderEnabled(
                finePermissionGranted = false,
                coarsePermissionGranted = true,
                gpsProviderEnabled = false,
                networkProviderEnabled = true,
            ),
        )
    }

    @Test
    fun finePermissionUsesEitherEnabledProvider() {
        assertTrue(
            setLogLocationProviderEnabled(
                finePermissionGranted = true,
                coarsePermissionGranted = false,
                gpsProviderEnabled = true,
                networkProviderEnabled = false,
            ),
        )
        assertFalse(
            setLogLocationProviderEnabled(
                finePermissionGranted = false,
                coarsePermissionGranted = false,
                gpsProviderEnabled = true,
                networkProviderEnabled = true,
            ),
        )
    }
}
