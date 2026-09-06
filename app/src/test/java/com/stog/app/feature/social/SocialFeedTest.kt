package com.stog.app.feature.social

import android.app.Application
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialFeedTest {
    @Test
    fun parsesTypedSignedThumbnailMediaWithoutTreatingTheObjectKeyAsAUrl() {
        val item = parseSocialFeedItem(JSONObject("""
            {
              "photo_id": 7,
              "trip_id": 9,
              "owner_id": 3,
              "owner_nickname": "여행하는 민지",
              "owner_profile_image_url": "https://storage.test/profile.jpg",
              "thumbnail_key": "photos/3/11111111-1111-1111-1111-111111111111-thumb.jpg",
              "thumbnail_media": {
                "state": "available",
                "signed_url": "https://storage.test/signed-thumbnail"
              },
              "caption": null,
              "cell_id": null,
              "lat": null,
              "lng": null,
              "taken_at": null,
              "like_count": 0,
              "liked_by_viewer": false,
              "saved_by_viewer": true,
              "created_at": "2026-08-21T00:00:00Z"
            }
        """.trimIndent()))

        assertEquals(SocialThumbnailMediaStatus.AVAILABLE, item.thumbnailMedia.status)
        assertEquals("https://storage.test/signed-thumbnail", item.thumbnailMedia.signedUrl)
        assertEquals("여행하는 민지", item.ownerNickname)
        assertEquals("https://storage.test/profile.jpg", item.ownerProfileImageUrl)
        assertFalse(item.thumbnailKey.startsWith("http"))
        assertTrue(item.savedByViewer)
    }

    @Test
    fun refreshMapsToHonestLoadingEmptyContentAndFailureStates() {
        assertEquals(SocialFeedUiState.Loading, initialSocialFeedState())
        assertEquals(
            SocialFeedUiState.Empty,
            socialRefreshSucceeded(SocialFeedPage(emptyList(), null)),
        )

        val page = SocialFeedPage(listOf(socialFeedItem(photoId = 7)), "next")
        assertEquals(
            SocialFeedUiState.Content(page.items, "next"),
            socialRefreshSucceeded(page),
        )
        assertEquals(
            SocialFeedUiState.Failure(SocialFeedFailure.AUTH_EXPIRED),
            socialRefreshFailed(SocialRequestException(401, "AUTH_EXPIRED")),
        )
        assertEquals(
            SocialFeedUiState.Failure(SocialFeedFailure.OFFLINE),
            socialRefreshFailed(IOException("offline")),
        )
        assertEquals(
            SocialFeedUiState.Failure(SocialFeedFailure.UNKNOWN),
            socialRefreshFailed(IllegalStateException("bad response")),
        )
    }

    @Test
    fun pagingKeepsLoadedItemsAndRepresentsProgressAndFailureWithoutFixtures() {
        val content = SocialFeedUiState.Content(
            items = listOf(socialFeedItem(photoId = 7), socialFeedItem(photoId = 6)),
            nextCursor = "next",
        )

        val loading = socialNextPageStarted(content)
        assertTrue(loading.loadingNext)
        assertEquals(null, loading.notice)

        val completed = socialNextPageSucceeded(
            loading,
            SocialFeedPage(
                items = listOf(socialFeedItem(photoId = 6), socialFeedItem(photoId = 5)),
                nextCursor = null,
            ),
        )
        assertEquals(listOf(7L, 6L, 5L), completed.items.map(SocialFeedItem::photoId))
        assertFalse(completed.loadingNext)
        assertEquals(null, completed.nextCursor)

        val failed = socialNextPageFailed(loading)
        assertEquals(listOf(7L, 6L), failed.items.map(SocialFeedItem::photoId))
        assertFalse(failed.loadingNext)
        assertEquals(SocialFeedNotice.NEXT_PAGE_FAILED, failed.notice)
    }

    @Test
    fun mergesPagesWithoutDuplicatePhotos() {
        val first = SocialFeedPage(
            items = listOf(socialFeedItem(photoId = 7), socialFeedItem(photoId = 6)),
            nextCursor = "next",
        )
        val second = SocialFeedPage(
            items = listOf(socialFeedItem(photoId = 6), socialFeedItem(photoId = 5)),
            nextCursor = null,
        )

        val merged = mergeSocialFeed(emptyList(), first)
        val completed = mergeSocialFeed(merged, second)

        assertEquals(listOf(7L, 6L, 5L), completed.map(SocialFeedItem::photoId))
    }

    @Test
    fun appliesServerLikeStateOnlyAfterRequestCompletes() {
        val item = socialFeedItem(photoId = 7).copy(likeCount = 3, likedByViewer = false)

        val liked = applySocialLikeState(item, SocialLikeState(7, 4, true))
        val unliked = applySocialLikeState(liked, SocialLikeState(7, 3, false))

        assertEquals(4L, liked.likeCount)
        assertTrue(liked.likedByViewer)
        assertEquals(3L, unliked.likeCount)
        assertFalse(unliked.likedByViewer)

        val content = SocialFeedUiState.Content(listOf(item), nextCursor = null)
        val updated = socialLikeSucceeded(content, SocialLikeState(7, 4, true))
        assertEquals(4L, updated.items.single().likeCount)
        assertTrue(updated.items.single().likedByViewer)
        assertEquals(SocialFeedNotice.LIKE_FAILED, socialLikeFailed(content).notice)
    }

    @Test
    fun appliesServerSaveStateToTheMatchingPhoto() {
        val item = socialFeedItem(photoId = 7).copy(savedByViewer = false)

        val saved = applySocialSaveState(item, SocialSaveState(7, true))
        val unsaved = applySocialSaveState(saved, SocialSaveState(7, false))

        assertTrue(saved.savedByViewer)
        assertFalse(unsaved.savedByViewer)
    }

    private fun socialFeedItem(photoId: Long) = SocialFeedItem(
        photoId = photoId,
        tripId = 9,
        ownerId = 3,
        thumbnailKey = "photos/$photoId/thumb.jpg",
        thumbnailMedia = SocialThumbnailMedia(
            SocialThumbnailMediaStatus.UNAVAILABLE,
            signedUrl = null,
        ),
        caption = null,
        cellId = null,
        latitude = null,
        longitude = null,
        takenAt = null,
        likeCount = 0,
        likedByViewer = false,
        createdAt = "2026-08-21T00:00:00Z",
    )
}
