package com.stog.app.feature.social

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.Role
import com.stog.app.ui.StogMediaPlaceholderKind
import com.stog.app.ui.stogMediaPlaceholderTag
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialThumbnailHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateIsExplicitBeforeSignedMediaCompletes() {
        composeRule.setContent {
            STOGTheme {
                SocialThumbnailContent(
                    photoId = 17,
                    state = SocialThumbnailState.Loading,
                )
            }
        }

        assertMachineState(SOCIAL_THUMBNAIL_LOADING, expectedRole = null)
    }

    @Test
    fun backendObjectKeyAndUnavailableSignedReadNeverRenderAsAvailableArtwork() {
        composeThumbnail(
            media = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.UNAVAILABLE,
                signedUrl = null,
            ),
            loader = { error("Unavailable media must not start a URL load") },
        )

        assertMachineState(SOCIAL_THUMBNAIL_UNAVAILABLE, expectedRole = null)
        assertPlaceholder()
    }

    @Test
    fun signedUrlSuccessRendersAvailableOnlyAfterTheLoaderReturnsDecodedMedia() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        composeThumbnail(
            media = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.AVAILABLE,
                signedUrl = "https://storage.test/signed-thumbnail",
            ),
            loader = { SocialThumbnailState.Available(bitmap) },
        )

        assertMachineState(SOCIAL_THUMBNAIL_AVAILABLE, expectedRole = Role.Image)
    }

    @Test
    fun signedUrlDecodeOrNetworkFailureRendersFailedWithoutBundledArtwork() {
        composeThumbnail(
            media = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.AVAILABLE,
                signedUrl = "https://storage.test/broken-thumbnail",
            ),
            loader = { SocialThumbnailState.Failed },
        )

        assertMachineState(SOCIAL_THUMBNAIL_FAILED, expectedRole = null)
        assertPlaceholder()
    }

    @Test
    fun backendFailedStateDoesNotAttemptTheSignedUrlLoader() {
        composeThumbnail(
            media = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.FAILED,
                signedUrl = null,
            ),
            loader = { error("Backend failed media must not start a URL load") },
        )

        assertMachineState(SOCIAL_THUMBNAIL_FAILED, expectedRole = null)
        assertPlaceholder()
    }

    private fun composeThumbnail(
        media: SocialThumbnailMedia,
        loader: suspend (String) -> SocialThumbnailState,
    ) {
        composeRule.setContent {
            STOGTheme {
                SocialThumbnail(
                    photoId = 17,
                    objectKey = "photos/42/11111111-1111-1111-1111-111111111111-thumb.jpg",
                    media = media,
                    loader = loader,
                    modifier = Modifier,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertPlaceholder() {
        val config = composeRule.onNodeWithTag(
            stogMediaPlaceholderTag(StogMediaPlaceholderKind.TOURISM),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config
        assertEquals("placeholder_tourism", config.getOrNull(SemanticsProperties.StateDescription))
        assertNull(config.getOrNull(SemanticsProperties.Role))
    }

    private fun assertMachineState(expectedState: String, expectedRole: Role?) {
        val config = composeRule.onNodeWithTag(socialThumbnailTestTag(17))
            .fetchSemanticsNode().config
        assertEquals(expectedState, config.getOrNull(SemanticsProperties.StateDescription))
        if (expectedRole == null) {
            assertNull(config.getOrNull(SemanticsProperties.Role))
        } else {
            assertEquals(expectedRole, config.getOrNull(SemanticsProperties.Role))
        }
    }
}
