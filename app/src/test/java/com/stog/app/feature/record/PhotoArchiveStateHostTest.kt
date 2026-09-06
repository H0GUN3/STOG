package com.stog.app.feature.record

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.stog.app.feature.space.CellBackground
import com.stog.app.feature.space.CellCoordinate
import com.stog.app.feature.space.CellDetail
import com.stog.app.feature.space.CellDetailsSheetContent
import com.stog.app.feature.space.CellDetailsState
import com.stog.app.feature.space.CellPhoto
import com.stog.app.feature.space.CellSummary
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.StogMediaPlaceholderKind
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogMediaPlaceholderTag
import com.stog.app.ui.stogStatePanelTag
import com.stog.app.ui.theme.STOGTheme
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
class PhotoArchiveStateHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun restoredArchiveWithoutTokenRendersRealAuthRecoveryWithTouchTarget() {
        var authenticationRequested = false
        composeRule.setContent {
            STOGTheme {
                PhotoArchiveScreen(
                    baseUrl = "https://unused.test",
                    accessToken = null,
                    viewerId = null,
                    onBack = {},
                    onAuthenticationRequired = { authenticationRequested = true },
                )
            }
        }

        val stateTag = stogStatePanelTag(StogSurfaceState.AUTH_EXPIRED)
        composeRule.onNodeWithTag(stateTag).fetchSemanticsNode()
        assertTouchTarget("photo_record_back_action")
        assertTouchTarget("${stateTag}_action").performClick()
        composeRule.runOnIdle { assertTrue(authenticationRequested) }
    }

    @Test
    fun productionArchiveFilterAndDetailControlsMeetTwoAxisTouchTargets() {
        val trip = TripSummary(7, "trip", "tour", "ended", "private", null, null)
        val photo = ArchivePhoto(
            id = 11,
            tripId = 7,
            userId = 3,
            source = PhotoSource.CAMERA,
            cellId = null,
            thumbnailUrl = null,
            caption = "note",
            visibility = PhotoVisibility.PRIVATE,
            createdAt = null,
            takenAt = null,
            placeResolutionStatus = "no_match",
            moderationStatus = "pending",
            publicationStatus = "private",
        )
        composeRule.setContent {
            STOGTheme {
                PhotoArchiveScreen(
                    baseUrl = "https://unused.test",
                    accessToken = "token",
                    viewerId = 3,
                    onBack = {},
                    onAuthenticationRequired = {},
                    initialTrips = listOf(trip),
                    initialArchive = listOf(photo),
                    autoLoad = false,
                )
            }
        }

        assertTouchTarget("photo_record_back_action")
        assertTouchTarget("archive_filter_control")
        composeRule.onNodeWithTag("photo_record_content")
            .performScrollToNode(hasTestTag("archive_detail_action:11"))
        assertTouchTarget("archive_detail_action:11")
        val metadataState = composeRule.onNodeWithTag(
            "archive_list_set_log_metadata:11",
            useUnmergedTree = true,
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(photo.readbackMetadata().stateDescription, metadataState)
    }

    @Test
    fun productionArchiveDetailPrimaryActionsMeetTwoAxisTouchTargets() {
        val detail = RemotePhoto(
            id = 11,
            tripId = 7,
            userId = 3,
            source = PhotoSource.CAMERA,
            cellId = null,
            latitude = null,
            longitude = null,
            takenAt = null,
            originalUrl = null,
            thumbnailUrl = null,
            caption = null,
            visibility = PhotoVisibility.PUBLIC,
            moderationStatus = "pending",
            createdAt = null,
        )
        composeRule.setContent {
            STOGTheme {
                PhotoArchiveScreen(
                    baseUrl = "https://unused.test",
                    accessToken = "token",
                    viewerId = 3,
                    onBack = {},
                    onAuthenticationRequired = {},
                    initialDetail = detail,
                    autoLoad = false,
                )
            }
        }

        assertTouchTarget("photo_record_back_action")
        val metadataState = composeRule.onNodeWithTag(
            "archive_detail_set_log_metadata:11",
            useUnmergedTree = true,
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(detail.readbackMetadata().stateDescription, metadataState)
        listOf(
            "archive_visibility_action:private",
            "archive_visibility_action:group",
            "archive_visibility_action:public",
            "archive_public_grant_action",
            "archive_download_action",
        ).forEach { tag ->
            composeRule.onNodeWithTag("photo_record_content")
                .performScrollToNode(hasTestTag(tag))
            assertTouchTarget(tag)
        }
    }

    @Test
    fun cellReadbackUsesTheSharedAccessibleMetadataSemantics() {
        val photo = CellPhoto(
            id = 31,
            cellId = "8a2a1072b59ffff",
            latitude = 35.815,
            longitude = 127.15,
            caption = "note",
            likeCount = 2,
            likedByViewer = false,
            visibilityScope = "group",
            takenAt = "2026-08-25T00:00:00Z",
            accuracyMeters = 8.5,
            placeId = null,
            placeName = "immutable snapshot",
            placeResolutionStatus = "matched",
            visibility = PhotoVisibility.PUBLIC,
            moderationStatus = "pending",
            publicationStatus = "moderation_pending",
        )
        val summary = CellSummary(
            cellId = photo.cellId,
            centroid = CellCoordinate(35.815, 127.15),
            boundary = listOf(
                CellCoordinate(35.815, 127.15),
                CellCoordinate(35.816, 127.15),
                CellCoordinate(35.816, 127.151),
            ),
            background = CellBackground.VISITED,
            badges = emptyList(),
            landmarkCount = 0,
            publicPhotoCount = 0,
            publicPhotoLikeCount = 0,
            topPhotoId = null,
            myVisitCount = 1,
            myPhotoCount = 1,
        )
        composeRule.setContent {
            STOGTheme {
                CellDetailsSheetContent(
                    state = CellDetailsState.Loaded(CellDetail(summary, emptyList()), listOf(photo)),
                    onClose = {},
                )
            }
        }

        val expected = setLogReadbackMetadata(
            placeId = photo.placeId,
            placeNameSnapshot = photo.placeName,
            placeResolutionStatus = photo.placeResolutionStatus,
            note = photo.caption,
            takenAt = photo.takenAt,
            visibility = photo.visibility,
            moderationStatus = photo.moderationStatus,
            publicationStatus = photo.publicationStatus,
            accuracyMeters = photo.accuracyMeters,
        ).stateDescription
        val actual = composeRule.onNodeWithTag(
            "cell_set_log_metadata:31",
            useUnmergedTree = true,
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(expected, actual)
    }

    @Test
    fun unavailableArchiveMediaExposesHonestMachineStateWithoutImageRole() {
        composeRule.setContent {
            STOGTheme { RemoteThumbnail(url = null, modifier = Modifier) }
        }

        val config = composeRule.onNodeWithTag("archive_thumbnail").fetchSemanticsNode().config
        assertEquals("unavailable", config.getOrNull(SemanticsProperties.StateDescription))
        assertNull(config.getOrNull(SemanticsProperties.Role))
        assertFalse(config.contains(SemanticsProperties.ContentDescription))
        val placeholder = composeRule.onNodeWithTag(
            stogMediaPlaceholderTag(StogMediaPlaceholderKind.TOURISM),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config
        assertEquals("placeholder_tourism", placeholder.getOrNull(SemanticsProperties.StateDescription))
        assertNull(placeholder.getOrNull(SemanticsProperties.Role))
    }

    private fun assertTouchTarget(tag: String) = composeRule.onNodeWithTag(tag)
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
}
