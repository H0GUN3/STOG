package com.stog.app.feature.record

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetLogCameraAdapterTest {
    @Test
    fun rearFirstCapabilitiesLabelUnavailableLensAndNoFlashExplicitly() {
        val frontOnly = setLogCameraCapabilities(
            rearAvailable = false,
            frontAvailable = true,
            selectedLens = SetLogCameraLens.FRONT,
            flashAvailable = false,
        )

        assertEquals("Rear camera", frontOnly.lenses[0].label)
        assertFalse(frontOnly.lensAvailable(SetLogCameraLens.REAR))
        assertTrue(frontOnly.lensAvailable(SetLogCameraLens.FRONT))
        assertEquals(SetLogCameraLens.FRONT, frontOnly.selectedLens)
        assertFalse(frontOnly.flashAvailable)
        assertEquals(listOf(SetLogFlashMode.OFF), frontOnly.supportedFlashModes)
        assertEquals(SetLogFlashMode.OFF, frontOnly.nextFlashMode())
    }

    @Test
    fun flashCyclesOnlyThroughSupportedOffAutoOnModes() {
        var capabilities = setLogCameraCapabilities(
            rearAvailable = true,
            frontAvailable = true,
            selectedLens = SetLogCameraLens.REAR,
            flashAvailable = true,
        )

        assertEquals(SetLogFlashMode.AUTO, capabilities.nextFlashMode())
        capabilities = capabilities.copy(selectedFlashMode = SetLogFlashMode.AUTO)
        assertEquals(SetLogFlashMode.ON, capabilities.nextFlashMode())
        capabilities = capabilities.copy(selectedFlashMode = SetLogFlashMode.ON)
        assertEquals(SetLogFlashMode.OFF, capabilities.nextFlashMode())
    }

    @Test
    fun callbacksAreSerializableAndMapToTokenCorrelatedReducerEvents() {
        val token = SetLogCaptureToken(7)
        val ready = SetLogCameraCallback.Ready(
            token,
            setLogCameraCapabilities(
                rearAvailable = true,
                frontAvailable = false,
                selectedLens = SetLogCameraLens.REAR,
                flashAvailable = true,
            ),
        )
        val captured = SetLogCameraCallback.Captured(token, "owned-output")
        val failed = SetLogCameraCallback.Error(token, SetLogCameraError.IMAGE_SAVE_FAILED)
        val unsupported = SetLogCameraCallback.Unavailable(token, SetLogCameraUnavailable.FRONT_LENS)

        listOf(ready, captured, failed, unsupported).forEach { callback ->
            ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(callback) }
        }
        assertEquals(SetLogCaptureEvent.CameraReady(token), ready.toCaptureEvent())
        assertEquals(
            SetLogCaptureEvent.CaptureCompleted(token, "owned-output"),
            captured.toCaptureEvent(),
        )
        assertEquals(
            SetLogCaptureEvent.CallbackFailed(token, "IMAGE_SAVE_FAILED"),
            failed.toCaptureEvent(),
        )
        assertEquals(
            SetLogCaptureEvent.CallbackFailed(token, "CAMERA_FRONT_LENS_UNAVAILABLE"),
            unsupported.toCaptureEvent(),
        )
    }

    @Test
    fun invalidationRepeatedRebindAndCloseRejectEveryStaleCallback() {
        val callbacks = mutableListOf<SetLogCameraCallback>()
        var mainThreadChecks = 0
        val gate = SetLogCameraCallbackGate(
            callback = callbacks::add,
            assertMainThread = { mainThreadChecks++ },
        )
        val first = SetLogCaptureToken(1)
        val second = SetLogCaptureToken(2)
        val firstGeneration = gate.begin(first)

        gate.invalidate()
        val secondGeneration = gate.begin(second)

        assertNotEquals(firstGeneration, secondGeneration)
        assertFalse(gate.dispatch(firstGeneration, SetLogCameraCallback.Captured(first, "stale")))
        assertTrue(gate.dispatch(secondGeneration, SetLogCameraCallback.Captured(second, "current")))
        gate.close()
        assertFalse(gate.dispatch(secondGeneration, SetLogCameraCallback.Captured(second, "late")))
        assertEquals(listOf(SetLogCameraCallback.Captured(second, "current")), callbacks)
        assertNull(gate.activeToken())
        assertTrue(mainThreadChecks > 0)
    }

    @Test
    fun adapterCallbacksDriveReadyCaptureAndExplicitBindFailureReducerStates() {
        var state = SetLogCaptureState("account-a", 17, 41)
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.Start(cameraPermissionGranted = true),
        ).state
        val token = requireNotNull(state.activeToken)
        val ready = SetLogCameraCallback.Ready(
            token,
            setLogCameraCapabilities(
                rearAvailable = true,
                frontAvailable = true,
                selectedLens = SetLogCameraLens.REAR,
                flashAvailable = true,
            ),
        )
        state = reduceSetLogCapture(state, requireNotNull(ready.toCaptureEvent())).state
        assertEquals(SetLogCaptureStage.READY, state.stage)

        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.ShutterPressed(
                takenAt = Instant.parse("2026-08-25T01:02:03Z"),
                location = SetLogLocationFix(
                    latitude = 35.815,
                    longitude = 127.15,
                    accuracyMeters = 12.5,
                    sourceTimeMillis = 1_777_000_000_000,
                    provenance = SetLogLocationFixProvenance.CURRENT_UPDATE,
                ),
                provisionalCellId = "8a30c43b16dffff",
                output = SetLogLocalAsset("owned", "owned-output", "owned.jpg"),
            ),
        ).state
        state = reduceSetLogCapture(
            state,
            requireNotNull(SetLogCameraCallback.Captured(token, "owned-output").toCaptureEvent()),
        ).state
        assertEquals(SetLogCaptureStage.DRAFT_READY, state.stage)

        var failedState = SetLogCaptureState("account-a", 17, 41)
        failedState = reduceSetLogCapture(
            failedState,
            SetLogCaptureEvent.Start(cameraPermissionGranted = true),
        ).state
        val failedToken = requireNotNull(failedState.activeToken)
        failedState = reduceSetLogCapture(
            failedState,
            requireNotNull(
                SetLogCameraCallback.Error(
                    failedToken,
                    SetLogCameraError.BIND_FAILED,
                ).toCaptureEvent(),
            ),
        ).state
        assertEquals(SetLogCaptureStage.ERROR, failedState.stage)
        assertEquals("BIND_FAILED", failedState.errorCode)
    }
}
