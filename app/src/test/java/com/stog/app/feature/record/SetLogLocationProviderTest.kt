package com.stog.app.feature.record

import android.location.LocationManager
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetLogLocationProviderTest {
    @Test
    fun fineAndCoarsePermissionsAreIndependent() {
        val coarseSource = FakeSource().apply { enabled += LocationManager.NETWORK_PROVIDER }
        var coarse: SetLogLocationAcquisition? = null
        provider(
            permissions = { SetLogLocationPermissionState(fineGranted = false, coarseGranted = true) },
            source = coarseSource,
        ).acquire { coarse = it }
        assertEquals(LocationManager.NETWORK_PROVIDER, coarseSource.requestedProvider)
        coarseSource.complete(SetLogSingleUpdate.Fix(freshFix()))
        assertTrue(coarse is SetLogLocationAcquisition.Accepted)

        val deniedSource = FakeSource()
        var denied: SetLogLocationAcquisition? = null
        provider(
            permissions = { SetLogLocationPermissionState(fineGranted = false, coarseGranted = false) },
            source = deniedSource,
        ).acquire { denied = it }
        assertRejected(SetLogLocationRejection.PERMISSION_DENIED, denied)
        assertEquals(0, deniedSource.requestCount)
    }

    @Test
    fun requestDenialAndRevocationFailClosed() {
        val deniedSource = FakeSource().apply {
            enabled += LocationManager.GPS_PROVIDER
            denyRequest = true
        }
        var denied: SetLogLocationAcquisition? = null
        provider(source = deniedSource).acquire { denied = it }
        assertRejected(SetLogLocationRejection.REQUEST_DENIED, denied)

        var granted = true
        val revokedSource = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var revoked: SetLogLocationAcquisition? = null
        provider(
            permissions = { SetLogLocationPermissionState(fineGranted = granted, coarseGranted = granted) },
            source = revokedSource,
        ).acquire { revoked = it }
        granted = false
        revokedSource.complete(SetLogSingleUpdate.Fix(freshFix()))
        assertRejected(SetLogLocationRejection.PERMISSION_REVOKED, revoked)
    }

    @Test
    fun providerOffIsRejectedBeforeRequest() {
        val source = FakeSource()
        var result: SetLogLocationAcquisition? = null
        provider(source = source).acquire { result = it }
        assertRejected(SetLogLocationRejection.PROVIDER_DISABLED, result)
        assertEquals(0, source.requestCount)

        val disabledDuringRequest = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var changed: SetLogLocationAcquisition? = null
        provider(source = disabledDuringRequest).acquire { changed = it }
        disabledDuringRequest.enabled.clear()
        disabledDuringRequest.complete(SetLogSingleUpdate.Fix(freshFix()))
        assertRejected(SetLogLocationRejection.PROVIDER_DISABLED, changed)
    }

    @Test
    fun timeoutAndCancellationCompleteByExactSignals() {
        val timeoutSource = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        val completed = CountDownLatch(1)
        var timeout: SetLogLocationAcquisition? = null
        provider(source = timeoutSource).acquire { timeout = it; completed.countDown() }
        timeoutSource.complete(SetLogSingleUpdate.Timeout)
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertRejected(SetLogLocationRejection.TIMEOUT, timeout)

        val cancelSource = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        val cancelled = CountDownLatch(1)
        cancelSource.onCancelled = { cancelled.countDown() }
        var callbacks = 0
        val request = provider(source = cancelSource).acquire { callbacks++ }
        request.cancel()
        request.cancel()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        cancelSource.complete(SetLogSingleUpdate.Fix(freshFix()))
        assertEquals(1, cancelSource.cancelCount)
        assertEquals(0, callbacks)
    }

    @Test
    fun nullInvalidStaleAndInaccurateUpdatesAreRejected() {
        assertUpdateRejected(null, SetLogLocationRejection.NULL_FIX)
        assertUpdateRejected(freshFix().copy(latitude = Double.NaN), SetLogLocationRejection.INVALID_FIX)
        assertUpdateRejected(
            freshFix().copy(sourceElapsedRealtimeNanos = MONOTONIC_NOW - 16_000_000_000L),
            SetLogLocationRejection.STALE_FIX,
        )
        assertUpdateRejected(freshFix().copy(accuracyMeters = 50.1), SetLogLocationRejection.INACCURATE_FIX)
    }

    @Test
    fun monotonicAgeWinsAndInvalidMonotonicFallsBackToWall() {
        val lastSource = FakeSource().apply {
            enabled += LocationManager.GPS_PROVIDER
            lastKnown[LocationManager.GPS_PROVIDER] = freshFix().copy(
                sourceTimeMillis = WALL_NOW - 120_000L,
                sourceElapsedRealtimeNanos = MONOTONIC_NOW - 5_000_000_000L,
            )
        }
        var monotonic: SetLogLocationAcquisition? = null
        provider(source = lastSource).acquire { monotonic = it }
        assertTrue(monotonic is SetLogLocationAcquisition.Accepted)
        assertEquals(SetLogLocationFixProvenance.LAST_KNOWN, (monotonic as SetLogLocationAcquisition.Accepted).fix.provenance)
        assertEquals(0, lastSource.requestCount)

        val fallbackSource = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var fallback: SetLogLocationAcquisition? = null
        provider(source = fallbackSource).acquire { fallback = it }
        fallbackSource.complete(SetLogSingleUpdate.Fix(freshFix().copy(
            sourceTimeMillis = WALL_NOW - 16_000L,
            sourceElapsedRealtimeNanos = MONOTONIC_NOW + 1L,
        )))
        assertRejected(SetLogLocationRejection.STALE_FIX, fallback)
    }

    @Test
    fun acceptedFixIsSerializableAndFreezesBeforeTakePhoto() {
        val source = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var result: SetLogLocationAcquisition? = null
        provider(source = source).acquire { result = it }
        source.complete(SetLogSingleUpdate.Fix(freshFix()))
        val accepted = result as SetLogLocationAcquisition.Accepted
        assertTrue(ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(accepted.fix) }
            bytes.size() > 0
        })

        val frozen = freezeSetLogShutter(
            readyState(),
            accepted,
            Instant.parse("2026-08-25T01:02:03Z"),
            asset("accepted"),
        )
        val snapshot = requireNotNull(frozen.state.pendingSnapshot)
        assertEquals(35.815, snapshot.coordinates!!.latitude, 0.0)
        assertEquals(12.5, snapshot.accuracyMeters!!, 0.0)
        assertNotNull(snapshot.provisionalCellId)
        assertEquals(1, frozen.effects.filterIsInstance<SetLogCaptureEffect.TakePhoto>().size)
    }

    @Test
    fun recentRecordingFixIsUsedBeforeStartingAnotherProviderRequest() {
        val source = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var result: SetLogLocationAcquisition? = null

        provider(source = source).acquire(
            recent = SetLogRecentLocation(
                provider = LocationManager.GPS_PROVIDER,
                observation = freshFix(),
            ),
        ) { result = it }

        val accepted = result as SetLogLocationAcquisition.Accepted
        assertEquals(SetLogLocationFixProvenance.RECENT_RECORDING, accepted.fix.provenance)
        assertEquals(0, source.requestCount)
    }

    @Test
    fun recentRecordingFixCacheIsScopedToAccountTripAndUser() {
        val recent = SetLogRecentLocation(
            provider = LocationManager.GPS_PROVIDER,
            observation = freshFix(),
        )
        SetLogRecentLocationCache.put("account-a", "41", "17", recent)

        assertEquals(
            recent,
            SetLogRecentLocationCache.get("account-a", "41", "17"),
        )
        assertNull(SetLogRecentLocationCache.get("account-b", "41", "17"))
        assertNull(SetLogRecentLocationCache.get("account-a", "42", "17"))
        assertNull(SetLogRecentLocationCache.get("account-a", "41", "18"))

        SetLogRecentLocationCache.remove("account-a", "41", "17")
    }

    @Test
    fun rejectedFixDoesNotBlockCameraAndGalleryNeverUsesProvider() {
        val blocked = freezeSetLogShutter(
            readyState(),
            SetLogLocationAcquisition.Rejected(SetLogLocationRejection.STALE_FIX),
            Instant.parse("2026-08-25T01:02:03Z"),
            asset("blocked"),
        )
        assertEquals(SetLogCaptureStage.CAPTURING, blocked.state.stage)
        assertEquals("LOCATION_STALE_FIX", blocked.state.errorCode)
        assertNull(blocked.state.pendingSnapshot?.coordinates)
        assertEquals(1, blocked.effects.filterIsInstance<SetLogCaptureEffect.TakePhoto>().size)

        val unusedSource = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var gallery = readyState()
        val token = requireNotNull(gallery.activeToken)
        gallery = reduceSetLogCapture(gallery, SetLogCaptureEvent.GalleryPressed).state
        gallery = reduceSetLogCapture(
            gallery,
            SetLogCaptureEvent.GallerySelected(
                token,
                asset("gallery"),
                SetLogGalleryProvenance(null, null),
                null,
            ),
        ).state
        assertEquals(SetLogCaptureStage.DRAFT_READY, gallery.stage)
        assertEquals(0, unusedSource.requestCount)
        assertNull(requireNotNull(gallery.draft).snapshot.coordinates)
        assertNull(requireNotNull(gallery.draft).snapshot.provisionalCellId)
    }

    @Test
    fun enabledProvidersStartTogetherAndNetworkFixCanWinBeforeGpsTimeout() {
        val source = FakeSource().apply {
            enabled += LocationManager.GPS_PROVIDER
            enabled += LocationManager.NETWORK_PROVIDER
        }
        var result: SetLogLocationAcquisition? = null

        provider(source = source).acquire { result = it }

        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            source.requestedProviders,
        )
        source.complete(LocationManager.NETWORK_PROVIDER, SetLogSingleUpdate.Fix(freshFix()))

        assertTrue(result is SetLogLocationAcquisition.Accepted)
        assertEquals(1, source.cancelCount)
    }

    @Test
    fun fusedProviderIsRequestedBeforeGpsAndNetwork() {
        assertEquals(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
            ),
            setLogLocationProviders(
                SetLogLocationPermissionState(fineGranted = true, coarseGranted = true),
                sdkInt = 35,
            ),
        )
    }

    @Test
    fun fusedFixWinsWhenNetworkRespondsFirst() {
        val source = FakeSource().apply {
            enabled += LocationManager.FUSED_PROVIDER
            enabled += LocationManager.NETWORK_PROVIDER
        }
        var result: SetLogLocationAcquisition? = null

        provider(source = source, maxAccuracyMeters = 100.0, sdkInt = 35).acquire { result = it }
        source.complete(
            LocationManager.NETWORK_PROVIDER,
            SetLogSingleUpdate.Fix(freshFix().copy(accuracyMeters = 80.0)),
        )
        assertNull(result)

        source.complete(
            LocationManager.FUSED_PROVIDER,
            SetLogSingleUpdate.Fix(
                freshFix().copy(latitude = 35.816, accuracyMeters = 12.0),
            ),
        )

        val accepted = result as SetLogLocationAcquisition.Accepted
        assertEquals(35.816, accepted.fix.latitude, 0.0)
    }

    @Test
    fun acceptedFusedLastKnownFixWinsOverNewerNetworkFix() {
        val source = FakeSource().apply {
            enabled += LocationManager.FUSED_PROVIDER
            enabled += LocationManager.NETWORK_PROVIDER
            lastKnown[LocationManager.FUSED_PROVIDER] = freshFix().copy(
                latitude = 35.816,
                accuracyMeters = 12.0,
            )
            lastKnown[LocationManager.NETWORK_PROVIDER] = freshFix().copy(
                latitude = 35.817,
                accuracyMeters = 80.0,
                sourceTimeMillis = WALL_NOW - 1_000L,
            )
        }
        var result: SetLogLocationAcquisition? = null

        provider(source = source, maxAccuracyMeters = 100.0, sdkInt = 35).acquire { result = it }

        val accepted = result as SetLogLocationAcquisition.Accepted
        assertEquals(35.816, accepted.fix.latitude, 0.0)
        assertEquals(0, source.requestCount)
    }

    @Test
    fun networkLastKnownFixDoesNotBypassCurrentFusedRequest() {
        val source = FakeSource().apply {
            enabled += LocationManager.FUSED_PROVIDER
            enabled += LocationManager.NETWORK_PROVIDER
            lastKnown[LocationManager.NETWORK_PROVIDER] = freshFix().copy(
                accuracyMeters = 80.0,
            )
        }
        var result: SetLogLocationAcquisition? = null

        provider(source = source, maxAccuracyMeters = 100.0, sdkInt = 35).acquire { result = it }

        assertNull(result)
        assertEquals(
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER),
            source.requestedProviders,
        )
    }

    @Test
    fun cameraPolicyCanAcceptAnApproximateFixWithinOneHundredMeters() {
        val source = FakeSource().apply { enabled += LocationManager.NETWORK_PROVIDER }
        var result: SetLogLocationAcquisition? = null

        provider(source = source, maxAccuracyMeters = 100.0).acquire { result = it }
        source.complete(
            SetLogSingleUpdate.Fix(freshFix().copy(accuracyMeters = 100.0)),
        )

        assertTrue(result is SetLogLocationAcquisition.Accepted)
    }

    private fun assertUpdateRejected(
        observation: SetLogLocationObservation?,
        reason: SetLogLocationRejection,
    ) {
        val source = FakeSource().apply { enabled += LocationManager.GPS_PROVIDER }
        var result: SetLogLocationAcquisition? = null
        provider(source = source).acquire { result = it }
        source.complete(SetLogSingleUpdate.Fix(observation))
        assertRejected(reason, result)
    }

    private fun assertRejected(reason: SetLogLocationRejection, result: SetLogLocationAcquisition?) {
        assertEquals(reason, (result as SetLogLocationAcquisition.Rejected).reason)
    }

    private fun provider(
        permissions: () -> SetLogLocationPermissionState = {
            SetLogLocationPermissionState(fineGranted = true, coarseGranted = true)
        },
        source: FakeSource,
        maxAccuracyMeters: Double = 50.0,
        sdkInt: Int = 0,
    ) = SetLogLocationProvider(
        permissions,
        source,
        SetLogLocationTuning(15_000L, maxAccuracyMeters, 8_000L),
        { WALL_NOW },
        { MONOTONIC_NOW },
        sdkInt,
    )

    private fun freshFix() = SetLogLocationObservation(
        35.815,
        127.15,
        12.5,
        WALL_NOW - 3_000L,
        MONOTONIC_NOW - 3_000_000_000L,
    )

    private fun readyState(): SetLogCaptureState {
        var state = SetLogCaptureState("account-a", 17, 41)
        state = reduceSetLogCapture(state, SetLogCaptureEvent.Start(cameraPermissionGranted = true)).state
        return reduceSetLogCapture(
            state,
            SetLogCaptureEvent.CameraReady(requireNotNull(state.activeToken)),
        ).state
    }

    private fun asset(id: String) = SetLogLocalAsset("$id-asset", "$id-output", "$id.jpg")

    private class FakeSource : SetLogLocationCallbackSource {
        val enabled = linkedSetOf<String>()
        val lastKnown = mutableMapOf<String, SetLogLocationObservation?>()
        var requestedProvider: String? = null
        val requestedProviders = mutableListOf<String>()
        var requestCount = 0
        var cancelCount = 0
        var denyRequest = false
        var onCancelled: () -> Unit = {}
        private val callbacks = mutableMapOf<String, (SetLogSingleUpdate) -> Unit>()

        override fun isProviderEnabled(provider: String) = provider in enabled
        override fun lastKnownLocation(provider: String) = lastKnown[provider]

        override fun requestSingleUpdate(
            provider: String,
            timeoutMillis: Long,
            callback: (SetLogSingleUpdate) -> Unit,
        ): SetLogLocationCancellation {
            if (denyRequest) throw SecurityException("denied")
            assertEquals(8_000L, timeoutMillis)
            requestCount++
            requestedProvider = provider
            requestedProviders += provider
            callbacks[provider] = callback
            return SetLogLocationCancellation {
                if (callbacks.remove(provider) != null) {
                    cancelCount++
                    onCancelled()
                }
            }
        }

        fun complete(update: SetLogSingleUpdate) {
            requestedProvider?.let { complete(it, update) }
        }

        fun complete(provider: String, update: SetLogSingleUpdate) {
            callbacks.remove(provider)?.invoke(update)
        }
    }

    private companion object {
        const val WALL_NOW = 1_777_250_526_000L
        const val MONOTONIC_NOW = 100_000_000_000L
    }
}
