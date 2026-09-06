package com.stog.app.feature.record

import com.stog.app.feature.space.TripSummary
import com.uber.h3core.H3Core
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetLogCaptureStateTest {
    @Test
    fun shutterLocationGateIsSingleFlightCancellableAndTokenCorrelatedBeforeOutputCreation() {
        val gate = SetLogShutterLocationGate()
        val firstToken = SetLogCaptureToken(1)
        val first = requireNotNull(gate.begin(firstToken))
        assertNull(gate.begin(firstToken))

        var cancellations = 0
        var outputsCreated = 0
        gate.attach(first) { cancellations++ }
        gate.cancel()
        assertEquals(1, cancellations)
        if (gate.deliver(first, firstToken, SetLogCaptureStage.READY)) outputsCreated++
        assertEquals(0, outputsCreated)

        val secondToken = SetLogCaptureToken(2)
        val second = requireNotNull(gate.begin(secondToken))
        assertFalse(gate.deliver(second, SetLogCaptureToken(3), SetLogCaptureStage.READY))
        assertEquals(0, outputsCreated)

        val current = requireNotNull(gate.begin(secondToken))
        assertTrue(gate.deliver(current, secondToken, SetLogCaptureStage.READY))
        outputsCreated++
        assertEquals(1, outputsCreated)

        val capture = requireNotNull(gate.begin(SetLogCaptureToken(3)))
        assertTrue(gate.deliver(capture, SetLogCaptureToken(3), SetLogCaptureStage.CAPTURING))
    }

    @Test
    fun staleCaptureAndPlaceCallbacksCannotMutateReboundOrRetakenState() {
        val ready = readyState()
        val firstToken = requireNotNull(ready.activeToken)
        val capturing = reduceSetLogCapture(
            ready,
            SetLogCaptureEvent.ShutterPressed(
                takenAt = Instant.parse("2026-08-25T01:02:03Z"),
                location = location(),
                provisionalCellId = "8a30c43b16dffff",
                output = asset("first"),
            ),
        ).state

        val rebound = reduceSetLogCapture(capturing, SetLogCaptureEvent.RebindRequested)
        val secondToken = requireNotNull(rebound.state.activeToken)

        assertNotEquals(firstToken, secondToken)
        assertEquals(SetLogCaptureStage.INITIALIZING, rebound.state.stage)
        assertEquals(
            listOf(
                SetLogCaptureEffect.CleanupAsset(asset("first")),
                SetLogCaptureEffect.BindCamera(secondToken),
            ),
            rebound.effects,
        )
        assertEquals(
            rebound.state,
            reduceSetLogCapture(
                rebound.state,
                SetLogCaptureEvent.CaptureCompleted(firstToken, "first-output"),
            ).state,
        )
        assertEquals(
            rebound.state,
            reduceSetLogCapture(
                rebound.state,
                SetLogCaptureEvent.PlacePreviewChanged(
                    firstToken,
                    SetLogPlacePreview.Matched(9, "stale"),
                ),
            ).state,
        )
    }

    @Test
    fun retakeAndCloseInvalidateTokensBeforeCleanupAndSuppressLateCallbacks() {
        val draftReady = cameraDraftReady()
        val oldToken = requireNotNull(draftReady.activeToken)
        val retaken = reduceSetLogCapture(draftReady, SetLogCaptureEvent.RetakePressed)
        val newToken = requireNotNull(retaken.state.activeToken)

        assertNotEquals(oldToken, newToken)
        assertNull(retaken.state.draft)
        assertEquals(SetLogCaptureStage.INITIALIZING, retaken.state.stage)
        assertTrue(retaken.effects.first() is SetLogCaptureEffect.CleanupAsset)
        assertEquals(SetLogCaptureEffect.BindCamera(newToken), retaken.effects.last())

        val closed = reduceSetLogCapture(draftReady, SetLogCaptureEvent.ClosePressed)
        assertEquals(SetLogCaptureStage.CLOSED, closed.state.stage)
        assertNull(closed.state.activeToken)
        assertNull(closed.state.draft)
        assertEquals(SetLogCaptureEffect.Close, closed.effects.last())
        assertEquals(
            closed.state,
            reduceSetLogCapture(
                closed.state,
                SetLogCaptureEvent.CaptureCompleted(oldToken, "camera-output"),
            ).state,
        )
    }

    @Test
    fun cameraBindFailureRetakeRequestsAFreshCameraBind() {
        val initializing = reduceSetLogCapture(
            SetLogCaptureState("account-a", 17, 41),
            SetLogCaptureEvent.Start(cameraPermissionGranted = true),
        ).state
        val failed = reduceSetLogCapture(
            initializing,
            SetLogCaptureEvent.CallbackFailed(requireNotNull(initializing.activeToken), "BIND_FAILED"),
        ).state

        val retaken = reduceSetLogCapture(failed, SetLogCaptureEvent.RetakePressed)

        assertEquals(SetLogCaptureStage.INITIALIZING, retaken.state.stage)
        assertEquals(
            SetLogCaptureEffect.BindCamera(requireNotNull(retaken.state.activeToken)),
            retaken.effects.single(),
        )
    }

    @Test
    fun imageSaveFailureKeepsCaptureInExplicitRetryableError() {
        val ready = readyState()
        val token = requireNotNull(ready.activeToken)
        val capturing = reduceSetLogCapture(
            ready,
            SetLogCaptureEvent.ShutterPressed(
                takenAt = Instant.parse("2026-08-25T01:02:03Z"),
                location = SetLogLocationFix(
                    latitude = 35.815,
                    longitude = 127.15,
                    accuracyMeters = 12.5,
                    sourceTimeMillis = 1_777_250_526_000L,
                    provenance = SetLogLocationFixProvenance.CURRENT_UPDATE,
                ),
                provisionalCellId = null,
                output = asset("capture"),
            ),
        ).state

        val failed = reduceSetLogCapture(
            capturing,
            SetLogCaptureEvent.CallbackFailed(token, "IMAGE_SAVE_FAILED"),
        ).state

        assertEquals(SetLogCaptureStage.ERROR, failed.stage)
        assertEquals("IMAGE_SAVE_FAILED", failed.errorCode)
    }

    @Test
    fun cameraCaptureStartsWithoutLocationAndAttachesFixBeforeRecord() {
        val ready = readyState()
        val token = requireNotNull(ready.activeToken)
        val shutter = freezeSetLogShutter(
            ready,
            SetLogLocationAcquisition.Rejected(SetLogLocationRejection.TIMEOUT),
            Instant.parse("2026-08-25T01:02:03Z"),
            asset("camera"),
        )

        assertEquals(SetLogCaptureStage.CAPTURING, shutter.state.stage)
        assertTrue(shutter.effects.any { it is SetLogCaptureEffect.TakePhoto })

        val draft = reduceSetLogCapture(
            shutter.state,
            SetLogCaptureEvent.CaptureCompleted(token, "camera-output"),
        ).state
        assertEquals(SetLogCaptureStage.DRAFT_READY, draft.stage)
        assertFalse(requireNotNull(draft.draft).canRecord)

        val located = reduceSetLogCapture(
            draft,
            SetLogCaptureEvent.LocationUpdated(token, SetLogLocationAcquisition.Accepted(location())),
        ).state
        assertEquals(PhotoCoordinates(35.815, 127.15), located.draft?.snapshot?.coordinates)
        assertTrue(requireNotNull(located.draft).canRecord)
    }

    @Test
    fun rejectedLocationKeepsExactReasonWhileCaptureContinues() {
        val reduction = freezeSetLogShutter(
            readyState(),
            SetLogLocationAcquisition.Rejected(SetLogLocationRejection.STALE_FIX),
            Instant.parse("2026-08-25T01:02:03Z"),
            asset("rejected"),
        )

        assertEquals(SetLogCaptureStage.CAPTURING, reduction.state.stage)
        assertEquals("LOCATION_STALE_FIX", reduction.state.errorCode)
        assertTrue(reduction.effects.any { it is SetLogCaptureEffect.TakePhoto })
    }

    @Test
    fun timeoutLocationRejectionStillEmitsCameraCaptureEffect() {
        val reduction = freezeSetLogShutter(
            readyState(),
            SetLogLocationAcquisition.Rejected(SetLogLocationRejection.TIMEOUT),
            Instant.parse("2026-08-25T01:02:03Z"),
            asset("rejected"),
        )

        assertTrue(reduction.effects.any { it is SetLogCaptureEffect.TakePhoto })
    }

    @Test
    fun cameraUsesInjectedShutterProvenanceAndGalleryUsesOnlyAbsoluteExif() {
        val camera = cameraDraftReady().draft!!.snapshot
        assertEquals(PhotoSource.CAMERA, camera.source)
        assertEquals("account-a", camera.accountId)
        assertEquals(17L, camera.userId)
        assertEquals(41L, camera.tripId)
        assertEquals("2026-08-25T01:02:03Z", camera.takenAt)
        assertEquals(SetLogTakenAtProvenance.CAMERA_SHUTTER, camera.takenAtProvenance)
        assertEquals(SetLogLocationProvenance.CAMERA_FOREGROUND, camera.locationProvenance)
        assertEquals(12.5, camera.accuracyMeters!!, 0.0)

        val withOffset = setLogGalleryProvenance(
            galleryExif("2026:08:25 10:02:03", "+09:00"),
            hasMediaLocationAccess = true,
        )
        assertEquals(PhotoCoordinates(35.815, 127.15), withOffset.coordinates)
        assertEquals(Instant.parse("2026-08-25T01:02:03Z"), withOffset.takenAt)

        val deniedLocation = setLogGalleryProvenance(
            galleryExif("2026:08:25 10:02:03", "+09:00"),
            hasMediaLocationAccess = false,
        )
        assertNull(deniedLocation.coordinates)
        assertEquals(Instant.parse("2026-08-25T01:02:03Z"), deniedLocation.takenAt)

        val timezoneUnknown = setLogGalleryProvenance(
            galleryExif("2026:08:25 10:02:03", null),
            hasMediaLocationAccess = true,
        )
        assertNull(timezoneUnknown.takenAt)

        val gpsUtc = setLogGalleryProvenance(
            galleryExif(
                dateTimeOriginal = null,
                offsetTimeOriginal = null,
                gpsDateStamp = "2026:08:25",
                gpsTimeStamp = "1/1,2/1,3/1",
            ),
            hasMediaLocationAccess = true,
        )
        assertEquals(Instant.parse("2026-08-25T01:02:03Z"), gpsUtc.takenAt)
    }

    @Test
    fun gallerySnapshotKeepsMissingExifUnknownAndNeverAcquiresCameraProvenance() {
        var state = readyState()
        val token = requireNotNull(state.activeToken)
        state = reduceSetLogCapture(state, SetLogCaptureEvent.GalleryPressed).state
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.GallerySelected(
                token = token,
                asset = asset("gallery"),
                provenance = SetLogGalleryProvenance(null, null),
                provisionalCellId = null,
            ),
        ).state

        val draft = requireNotNull(state.draft)
        val snapshot = draft.snapshot
        assertEquals(PhotoSource.GALLERY, snapshot.source)
        assertNull(snapshot.coordinates)
        assertNull(snapshot.takenAt)
        assertNull(snapshot.accuracyMeters)
        assertEquals(SetLogLocationProvenance.MISSING, snapshot.locationProvenance)
        assertEquals(SetLogTakenAtProvenance.MISSING, snapshot.takenAtProvenance)
        assertEquals(SetLogPlacePreview.NoMatch, draft.placePreview)
    }

    @Test
    fun noteLimitCountsUnicodeCodePointsAndRejectsEveryLineBreak() {
        val max = "\uD83D\uDCF7".repeat(SET_LOG_NOTE_MAX_CODE_POINTS)
        assertNull(setLogNoteIssue(max))
        assertEquals(SetLogNoteIssue.TOO_LONG, setLogNoteIssue(max + "x"))
        assertEquals(SetLogNoteIssue.MULTILINE, setLogNoteIssue("line one\nline two"))
        assertEquals(SetLogNoteIssue.MULTILINE, setLogNoteIssue("line one\rline two"))
        assertEquals(SetLogNoteIssue.MULTILINE, setLogNoteIssue("line one\u2028line two"))
        assertEquals(SetLogNoteIssue.MULTILINE, setLogNoteIssue("line one\u2029line two"))
    }

    @Test
    fun recordIsTheSingleCommitTransitionAndFrozenMetadataSurvivesEdits() {
        var state = cameraDraftReady()
        val frozen = state.draft!!.snapshot
        assertTrue(reduceSetLogCapture(state, SetLogCaptureEvent.RecordPressed).effects.isEmpty())

        state = reduceSetLogCapture(state, SetLogCaptureEvent.ConfirmationOpened).state
        state = reduceSetLogCapture(state, SetLogCaptureEvent.CaptionChanged("  one moment  ")).state
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.VisibilityChanged(SetLogVisibilityIntent.PUBLIC),
        ).state
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.PlacePreviewChanged(
                requireNotNull(state.activeToken),
                SetLogPlacePreview.Matched(7, "place"),
            ),
        ).state

        val edited = requireNotNull(state.draft)
        assertEquals(frozen, edited.snapshot)
        assertEquals("one moment", edited.captionForRecord)
        val firstRecord = reduceSetLogCapture(state, SetLogCaptureEvent.RecordPressed)
        assertEquals(SetLogCaptureStage.SAVING, firstRecord.state.stage)
        assertEquals(1, firstRecord.effects.filterIsInstance<SetLogCaptureEffect.CommitRecord>().size)
        assertTrue(
            reduceSetLogCapture(firstRecord.state, SetLogCaptureEvent.RecordPressed)
                .effects.filterIsInstance<SetLogCaptureEffect.CommitRecord>().isEmpty(),
        )

        val invalid = reduceSetLogCapture(state, SetLogCaptureEvent.CaptionChanged("x\ny")).state
        assertEquals(SetLogNoteIssue.MULTILINE, invalid.draft!!.captionIssue)
        assertTrue(reduceSetLogCapture(invalid, SetLogCaptureEvent.RecordPressed).effects.isEmpty())
    }

    @Test
    fun durableFailureRejectsRetakeCleanupWhilePreCommitFailureAcceptsIt() {
        var confirming = cameraDraftReady()
        confirming = reduceSetLogCapture(confirming, SetLogCaptureEvent.ConfirmationOpened).state
        val saving = reduceSetLogCapture(confirming, SetLogCaptureEvent.RecordPressed).state
        val token = requireNotNull(saving.activeToken)

        val preCommitFailure = reduceSetLogCapture(
            saving,
            SetLogCaptureEvent.DeliveryFailed(token, "SET_LOG_PERSIST_FAILED", retryable = false),
        )
        assertEquals(SetLogCaptureStage.FAILED, preCommitFailure.state.stage)
        assertFalse(preCommitFailure.state.hasDurablePendingRecord)
        assertTrue(
            reduceSetLogCapture(preCommitFailure.state, SetLogCaptureEvent.RetakePressed)
                .effects.any { it is SetLogCaptureEffect.CleanupAsset },
        )

        val persisted = reduceSetLogCapture(saving, SetLogCaptureEvent.RecordPersisted(token)).state
        val durableFailure = reduceSetLogCapture(
            persisted,
            SetLogCaptureEvent.DeliveryFailed(token, "PHOTO_REJECTED", retryable = false),
        )
        assertEquals(SetLogCaptureStage.FAILED, durableFailure.state.stage)
        assertTrue(durableFailure.state.hasDurablePendingRecord)
        assertTrue(reduceSetLogCapture(durableFailure.state, SetLogCaptureEvent.RetakePressed).effects.isEmpty())
        assertTrue(
            reduceSetLogCapture(durableFailure.state, SetLogCaptureEvent.ClosePressed)
                .effects.none { it is SetLogCaptureEffect.CleanupAsset },
        )
    }

    @Test
    fun provisionalCellAndTripPreflightAreDeterministic() {
        val coordinates = PhotoCoordinates(35.815, 127.15)
        val cell = requireNotNull(provisionalSetLogCellId(coordinates))
        val h3 = H3Core.newInstance()
        assertEquals(SET_LOG_H3_RESOLUTION, h3.getResolution(h3.stringToH3(cell)))
        assertNull(provisionalSetLogCellId(PhotoCoordinates(Double.NaN, 127.15)))

        val trips = listOf(
            TripSummary(9, "ended", "tour", "ended", "private"),
            TripSummary(4, "second", "tour", "dormant", "private"),
            TripSummary(2, "first", "tour", "active", "private"),
        )
        assertEquals(
            listOf(2L, 4L),
            (setLogTripPreflight(trips) as SetLogTripPreflight.Choose).trips.map(TripSummary::id),
        )
        assertEquals(SetLogTripPreflight.NoActiveTrip, setLogTripPreflight(trips.take(1)))
    }

    private fun readyState(): SetLogCaptureState {
        var state = SetLogCaptureState("account-a", 17, 41)
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.Start(cameraPermissionGranted = true),
        ).state
        return reduceSetLogCapture(
            state,
            SetLogCaptureEvent.CameraReady(requireNotNull(state.activeToken)),
        ).state
    }

    private fun cameraDraftReady(): SetLogCaptureState {
        var state = readyState()
        val token = requireNotNull(state.activeToken)
        state = reduceSetLogCapture(
            state,
            SetLogCaptureEvent.ShutterPressed(
                takenAt = Instant.parse("2026-08-25T01:02:03Z"),
                location = location(),
                provisionalCellId = "8a30c43b16dffff",
                output = asset("camera"),
            ),
        ).state
        return reduceSetLogCapture(
            state,
            SetLogCaptureEvent.CaptureCompleted(token, "camera-output"),
        ).state
    }

    private fun location() = SetLogLocationFix(
        latitude = 35.815,
        longitude = 127.15,
        accuracyMeters = 12.5,
        sourceTimeMillis = 1_777_250_523_000L,
        provenance = SetLogLocationFixProvenance.CURRENT_UPDATE,
    )

    private fun asset(id: String) = SetLogLocalAsset(
        assetId = "$id-asset",
        outputId = "$id-output",
        path = "$id.jpg",
    )

    private fun galleryExif(
        dateTimeOriginal: String?,
        offsetTimeOriginal: String?,
        gpsDateStamp: String? = null,
        gpsTimeStamp: String? = null,
    ) = SetLogGalleryExif(
        coordinates = PhotoCoordinates(35.815, 127.15),
        dateTimeOriginal = dateTimeOriginal,
        offsetTimeOriginal = offsetTimeOriginal,
        gpsDateStamp = gpsDateStamp,
        gpsTimeStamp = gpsTimeStamp,
    )
}
