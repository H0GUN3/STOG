package com.stog.app.feature.record

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.stog.app.core.database.PendingSetLogEntity
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.STOGTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SetLogCameraHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun captureHasExactlyFiveTaggedButtonControlsWithRequiredBoundsAndAvailability() {
        val capabilities = setLogCameraCapabilities(
            rearAvailable = true,
            frontAvailable = true,
            selectedLens = SetLogCameraLens.REAR,
            flashAvailable = true,
        )
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.READY,
                    capabilities = capabilities,
                    locationFeedback = SetLogLocationFeedback.AVAILABLE,
                    placeLabel = "place",
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                )
            }
        }

        SET_LOG_CAPTURE_CONTROL_TAGS.forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .assertHasClickAction()
                .assertWidthIsAtLeast(StogUiContract.MinTouchTargetDp.dp)
                .assertHeightIsAtLeast(StogUiContract.MinTouchTargetDp.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsEnabled()
        }
        composeRule.onNodeWithTag(SET_LOG_CONFIRMATION_TAG).assertDoesNotExist()
    }

    @Test
    fun unsupportedFlashAndSingleLensStayPresentButUnavailable() {
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.READY,
                    capabilities = setLogCameraCapabilities(
                        rearAvailable = true,
                        frontAvailable = false,
                        selectedLens = SetLogCameraLens.REAR,
                        flashAvailable = false,
                    ),
                    locationFeedback = SetLogLocationFeedback.PROVIDER_DISABLED,
                    placeLabel = null,
                    onClose = {}, onFlash = {}, onGallery = {}, onShutter = {}, onSwitchLens = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_FLASH_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(SET_LOG_SWITCH_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_location_feedback").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "provider_disabled"),
        )
    }

    @Test
    fun disabledProviderOffersLocationSettingsAction() {
        var settingsLaunches = 0
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.READY,
                    capabilities = setLogCameraCapabilities(
                        rearAvailable = true,
                        frontAvailable = false,
                        selectedLens = SetLogCameraLens.REAR,
                        flashAvailable = false,
                    ),
                    locationFeedback = SetLogLocationFeedback.PROVIDER_DISABLED,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                    onLocationSettings = { settingsLaunches++ },
                )
            }
        }

        composeRule.onNodeWithTag("set_log_location_settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settingsLaunches) }
    }

    @Test
    fun timeoutFeedbackExplainsWhyCaptureCannotStart() {
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.ERROR,
                    capabilities = null,
                    locationFeedback = SetLogLocationFeedback.TIMEOUT,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                )
            }
        }

        composeRule.onNodeWithTag("set_log_location_feedback").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "timeout"),
        )
    }

    @Test
    fun missingPermissionOffersAppSettingsAction() {
        var settingsLaunches = 0
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.PERMISSION_REQUIRED,
                    capabilities = setLogCameraCapabilities(
                        rearAvailable = true,
                        frontAvailable = false,
                        selectedLens = SetLogCameraLens.REAR,
                        flashAvailable = false,
                    ),
                    locationFeedback = SetLogLocationFeedback.PERMISSION_REQUIRED,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                    onPermissionSettings = { settingsLaunches++ },
                )
            }
        }

        composeRule.onNodeWithTag("set_log_permission_settings").performClick()
        composeRule.runOnIdle { assertEquals(1, settingsLaunches) }
    }

    @Test
    fun shutterStaysDisabledWhileCameraBindingIsPending() {
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.INITIALIZING,
                    capabilities = null,
                    locationFeedback = SetLogLocationFeedback.ACQUIRING,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_SHUTTER_TAG).assertIsNotEnabled()
    }

    @Test
    fun shutterStaysDisabledWhileLocationFixIsPending() {
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.READY,
                    capabilities = setLogCameraCapabilities(
                        rearAvailable = true,
                        frontAvailable = false,
                        selectedLens = SetLogCameraLens.REAR,
                        flashAvailable = false,
                    ),
                    locationFeedback = SetLogLocationFeedback.ACQUIRING,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = {},
                    onSwitchLens = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_SHUTTER_TAG).assertIsNotEnabled()
    }

    @Test
    fun readyShutterDeliversOneClickToCaptureHandler() {
        var clicks = 0
        composeRule.setContent {
            STOGTheme {
                SetLogCaptureSurface(
                    previewView = null,
                    stage = SetLogCaptureStage.READY,
                    capabilities = setLogCameraCapabilities(
                        rearAvailable = true,
                        frontAvailable = false,
                        selectedLens = SetLogCameraLens.REAR,
                        flashAvailable = false,
                    ),
                    locationFeedback = SetLogLocationFeedback.AVAILABLE,
                    placeLabel = null,
                    onClose = {},
                    onFlash = {},
                    onGallery = {},
                    onShutter = { clicks++ },
                    onSwitchLens = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_SHUTTER_TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun confirmationExcludesCaptureControlsAndExposesFrozenTruthfulMetadata() {
        val draft = cameraDraft(
            place = SetLogPlacePreview.Matched(44, "place"),
            visibility = SetLogVisibilityIntent.PUBLIC,
        )
        composeRule.setContent {
            STOGTheme {
                SetLogConfirmationSurface(
                    draft = draft,
                    previewPath = null,
                    stage = SetLogCaptureStage.CONFIRMING,
                    onCaptionChanged = {},
                    onVisibilityChanged = {},
                    onRetake = {},
                    onRecord = {},
                    onClose = {},
                )
            }
        }

        listOf(SET_LOG_FLASH_TAG, SET_LOG_GALLERY_TAG, SET_LOG_SHUTTER_TAG, SET_LOG_SWITCH_TAG).forEach {
            composeRule.onNodeWithTag(it).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("set_log_metadata:place").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "place:matched:44:place"),
        )
        composeRule.onNodeWithTag("set_log_metadata:capture_time").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
        )
        composeRule.onNodeWithTag("set_log_metadata:source_provenance").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "source_provenance:camera_foreground"),
        )
        composeRule.onNodeWithTag("set_log_location_cell").assertExists()
        composeRule.onNodeWithTag("set_log_location_map").assertDoesNotExist()
        composeRule.onNodeWithTag("set_log_caption").assertExists()
        composeRule.onNodeWithTag("set_log_visibility:public").assertExists()
        composeRule.onNodeWithTag("set_log_publication_explanation").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "public_immediate_discovery",
            ),
        )
    }

    @Test
    fun visibilitySelectorExposesRadioSemanticsAndClickActions() {
        val draft = cameraDraft()
        composeRule.setContent {
            STOGTheme {
                SetLogConfirmationSurface(
                    draft = draft,
                    previewPath = null,
                    stage = SetLogCaptureStage.CONFIRMING,
                    onCaptionChanged = {},
                    onVisibilityChanged = {},
                    onRetake = {},
                    onRecord = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("set_log_visibility:private")
            .assertIsSelected()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithTag("set_log_visibility:public")
            .assertIsNotSelected()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun cameraWithoutLocationKeepsRecordDisabledAndExplainsWhy() {
        val captured = cameraDraft().snapshot
        val draft = cameraDraft().copy(
            snapshot = captured.copy(
                coordinates = null,
                accuracyMeters = null,
                locationProvenance = SetLogLocationProvenance.MISSING,
                provisionalCellId = null,
            ),
        )
        composeRule.setContent {
            STOGTheme {
                SetLogConfirmationSurface(
                    draft = draft,
                    previewPath = null,
                    stage = SetLogCaptureStage.CONFIRMING,
                    onCaptionChanged = {},
                    onVisibilityChanged = {},
                    onRetake = {},
                    onRecord = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_location_notice").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "camera_location_required",
            ),
        )
    }

    @Test
    fun failedDurableRecordDoesNotExposeDeadCommitActions() {
        composeRule.setContent {
            STOGTheme {
                SetLogConfirmationSurface(
                    draft = cameraDraft(),
                    previewPath = null,
                    stage = SetLogCaptureStage.FAILED,
                    hasDurablePendingRecord = true,
                    onCaptionChanged = {},
                    onVisibilityChanged = {},
                    onRetake = {},
                    onRecord = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(SET_LOG_RETAKE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_caption").assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_visibility:private").assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_visibility:public").assertIsNotEnabled()
    }

    @Test
    fun galleryWithoutExifIsArchiveOnlyWithUnknownTime() {
        val draft = galleryDraft()
        assertEquals("gallery_archive_only_time_unknown", galleryProvenanceValue(draft.snapshot))
        assertEquals("날짜 정보 없음", formatSetLogTime(draft.snapshot.takenAt))
        assertEquals(SetLogPlacePreview.NoMatch, draft.placePreview)

        val pending = pendingSetLogEntity(draft, prepared(PhotoSource.GALLERY, takenAt = null), 9)
        assertNull(pending.latitude)
        assertNull(pending.longitude)
        assertNull(pending.takenAt)
        assertNull(pending.locationProvenance)
        assertEquals("no_match", pending.placeResolutionStatus)
        composeRule.setContent {
            STOGTheme {
                SetLogConfirmationSurface(
                    draft = draft,
                    previewPath = null,
                    stage = SetLogCaptureStage.CONFIRMING,
                    onCaptionChanged = {},
                    onVisibilityChanged = {},
                    onRetake = {},
                    onRecord = {},
                    onClose = {},
                )
            }
        }
        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).assertIsEnabled()
        composeRule.onNodeWithTag("set_log_location_notice").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "gallery_archive_only",
            ),
        )
        composeRule.onNodeWithTag("set_log_location_map").assertDoesNotExist()
        composeRule.onNodeWithTag("set_log_metadata:source_provenance").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "source_provenance:gallery_archive_only_time_unknown",
            ),
        )
    }

    @Test
    fun noteLimitAndRapidDoubleRecordProduceOneDurableCommit() {
        var commits = 0
        composeRule.setContent {
            STOGTheme {
                var state by remember { mutableStateOf(confirming(cameraDraft())) }
                SetLogConfirmationSurface(
                    draft = requireNotNull(state.draft),
                    previewPath = null,
                    stage = state.stage,
                    onCaptionChanged = { state = reduceSetLogCapture(state, SetLogCaptureEvent.CaptionChanged(it)).state },
                    onVisibilityChanged = { state = reduceSetLogCapture(state, SetLogCaptureEvent.VisibilityChanged(it)).state },
                    onRetake = {},
                    onRecord = {
                        val reduction = reduceSetLogCapture(state, SetLogCaptureEvent.RecordPressed)
                        commits += reduction.effects.filterIsInstance<SetLogCaptureEffect.CommitRecord>().size
                        state = reduction.state
                    },
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("set_log_caption").performTextReplacement("x".repeat(SET_LOG_NOTE_MAX_CODE_POINTS + 1))
        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("set_log_caption").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "too_long"),
        )
        composeRule.onNodeWithTag("set_log_caption").performTextReplacement("valid")
        composeRule.onNodeWithTag("set_log_caption").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "valid"),
        )
        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).performClick()
        composeRule.onNodeWithTag(SET_LOG_RECORD_TAG).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, commits) }
    }

    @Test
    fun confirmedPayloadKeepsFrozenCameraProvenanceAndPublicConsent() {
        val draft = cameraDraft(
            place = SetLogPlacePreview.Matched(44, "place"),
            visibility = SetLogVisibilityIntent.PUBLIC,
        ).copy(caption = "note")
        val entity: PendingSetLogEntity = pendingSetLogEntity(
            draft,
            prepared(PhotoSource.CAMERA, "2026-08-25T01:02:03Z"),
            11,
        )

        assertEquals("camera_foreground", entity.locationProvenance)
        assertEquals(35.8, entity.latitude ?: Double.NaN, 0.0)
        assertEquals(127.1, entity.longitude ?: Double.NaN, 0.0)
        assertEquals("2026-08-25T01:02:03Z", entity.takenAt)
        assertEquals("matched", entity.placeResolutionStatus)
        assertEquals(44L, entity.expectedPlaceId)
        assertEquals("public", entity.visibility)
        assertTrue(entity.publicConsent)
        assertEquals("note", entity.caption)
        assertFalse(entity.clientUploadId.isBlank())
    }

    private fun cameraDraft(
        place: SetLogPlacePreview = SetLogPlacePreview.Pending,
        visibility: SetLogVisibilityIntent = SetLogVisibilityIntent.PRIVATE,
    ) = SetLogDraft(
        snapshot = SetLogSnapshot(
            accountId = "7",
            userId = 7,
            tripId = 9,
            source = PhotoSource.CAMERA,
            captureToken = SetLogCaptureToken(1),
            takenAt = "2026-08-25T01:02:03Z",
            takenAtProvenance = SetLogTakenAtProvenance.CAMERA_SHUTTER,
            coordinates = PhotoCoordinates(35.8, 127.1),
            accuracyMeters = 8.0,
            locationProvenance = SetLogLocationProvenance.CAMERA_FOREGROUND,
            provisionalCellId = "8a30c43b16dffff",
            localAsset = SetLogLocalAsset("asset", "output", "source.jpg"),
        ),
        placePreview = place,
        visibilityIntent = visibility,
    )

    private fun galleryDraft() = SetLogDraft(
        snapshot = SetLogSnapshot(
            accountId = "7",
            userId = 7,
            tripId = 9,
            source = PhotoSource.GALLERY,
            captureToken = SetLogCaptureToken(1),
            takenAt = null,
            takenAtProvenance = SetLogTakenAtProvenance.MISSING,
            coordinates = null,
            accuracyMeters = null,
            locationProvenance = SetLogLocationProvenance.MISSING,
            provisionalCellId = null,
            localAsset = SetLogLocalAsset("asset", "output", "source.jpg"),
        ),
    )

    private fun confirming(draft: SetLogDraft) = SetLogCaptureState(
        accountId = draft.snapshot.accountId,
        userId = draft.snapshot.userId,
        tripId = draft.snapshot.tripId,
        stage = SetLogCaptureStage.CONFIRMING,
        activeToken = draft.snapshot.captureToken,
        nextToken = 2,
        draft = draft,
    )

    private fun prepared(source: PhotoSource, takenAt: String?) = PreparedPhoto(
        id = "11111111-1111-1111-1111-111111111111",
        source = source,
        tripId = 9,
        normalizedOriginalPath = "original.jpg",
        thumbnailPath = "thumbnail.jpg",
        normalizedOriginalBytes = 1,
        thumbnailBytes = 1,
        originalSha256 = "a".repeat(64),
        thumbnailSha256 = "b".repeat(64),
        coordinates = if (source == PhotoSource.CAMERA) PhotoCoordinates(35.8, 127.1) else null,
        takenAt = takenAt,
        caption = null,
        sourceFilePath = "source.jpg",
    )
}
