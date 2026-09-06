package com.stog.app.feature.record

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogStatePanelTag
import com.stog.app.ui.theme.STOGTheme
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoCaptureStateHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun dailyRecordEntryOffersNonTripCapture() {
        var clicked = false
        composeRule.setContent {
            STOGTheme {
                DailyRecordEntryCard(
                    creating = false,
                    onClick = { clicked = true },
                )
            }
        }

        composeRule.onNodeWithText("일상").assertWidthIsAtLeast(1.dp)
        composeRule.onNodeWithText("여행이 아니어도 지금을 기록해요").assertWidthIsAtLeast(1.dp)
        composeRule.onNodeWithContentDescription("일상 기록").performClick()
        composeRule.runOnIdle { assertEquals(true, clicked) }
    }

    @Test
    fun typedRequestFailuresKeepAuthOfflineTerminalAndUnknownFinalizeDistinct() {
        val photo = preparedPhoto()
        assertEquals(
            PhotoUploadUiState.AuthenticationRequired(photo),
            photoFailureState(photo, UploadStage.REQUESTING_URLS, PhotoRequestException(401, "AUTH")),
        )
        assertEquals(
            PhotoUploadUiState.RetryableFailure(photo, UploadStage.REQUESTING_URLS, "RATE"),
            photoFailureState(photo, UploadStage.REQUESTING_URLS, PhotoRequestException(429, "RATE")),
        )
        assertEquals(
            PhotoUploadUiState.TerminalFailure(photo, UploadStage.REQUESTING_URLS, "POLICY"),
            photoFailureState(photo, UploadStage.REQUESTING_URLS, PhotoRequestException(422, "POLICY")),
        )
        assertEquals(
            PhotoUploadUiState.FinalizationUnknown(photo),
            photoFailureState(photo, UploadStage.FINALIZING, IOException("unknown")),
        )
        assertNull(PhotoUploadStateMachine().apply {
            failed(photo, UploadStage.REQUESTING_URLS, PhotoRequestException(422, "POLICY"))
        }.retry())
    }

    @Test
    fun productionStatusContentExposesRetryOnlyForRetryableOfflineFailure() {
        val photo = preparedPhoto()
        composeRule.setContent {
            STOGTheme {
                UploadStatusCard(
                    state = PhotoUploadUiState.RetryableFailure(photo, UploadStage.REQUESTING_URLS, "NETWORK"),
                    onUpload = {},
                    onRetry = {},
                )
            }
        }

        val actionTag = "${stogStatePanelTag(StogSurfaceState.OFFLINE)}_action"
        composeRule.onNodeWithTag(actionTag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun completedCaptureExposesOneUserTriggeredDownloadAction() {
        val remote = RemotePhoto(
            7, 9, 3, PhotoSource.CAMERA, null, null, null, null,
            "https://storage.test/original", null, null, PhotoVisibility.PRIVATE, "pending", null,
        )
        var downloaded: RemotePhoto? = null
        composeRule.setContent {
            STOGTheme {
                UploadStatusCard(
                    state = PhotoUploadUiState.Completed(remote),
                    onUpload = {},
                    onRetry = {},
                    onDownload = { downloaded = it },
                )
            }
        }

        composeRule.onNodeWithTag("capture_download_action")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(remote, downloaded) }
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun productionTerminalStatusHasNoInappropriateRetry() {
        val photo = preparedPhoto()
        composeRule.setContent {
            STOGTheme {
                UploadStatusCard(
                    state = PhotoUploadUiState.TerminalFailure(photo, UploadStage.REQUESTING_URLS, "POLICY"),
                    onUpload = {},
                    onRetry = { error("Terminal failures must not retry") },
                )
            }
        }

        composeRule.onNodeWithTag(stogStatePanelTag(StogSurfaceState.ERROR)).fetchSemanticsNode()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private fun preparedPhoto() = PreparedPhoto(
        id = "11111111-1111-1111-1111-111111111111",
        source = PhotoSource.GALLERY,
        tripId = 7,
        normalizedOriginalPath = "original",
        thumbnailPath = "thumbnail",
        normalizedOriginalBytes = 1,
        thumbnailBytes = 1,
        originalSha256 = "a".repeat(64),
        thumbnailSha256 = "b".repeat(64),
        coordinates = null,
        takenAt = null,
        caption = null,
    )
}
