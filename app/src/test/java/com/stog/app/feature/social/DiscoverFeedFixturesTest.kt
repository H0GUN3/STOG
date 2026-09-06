package com.stog.app.feature.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverFeedFixturesTest {
    @Test
    fun togglingLikeChangesViewerStateAndCount() {
        val post = fixturePost(likeCount = 3L)

        val liked = post.toggleLike()
        val unliked = liked.toggleLike()

        assertTrue(liked.item.likedByViewer)
        assertEquals(4L, liked.item.likeCount)
        assertFalse(unliked.item.likedByViewer)
        assertEquals(3L, unliked.item.likeCount)
    }

    @Test
    fun addingCommentKeepsExistingCommentsAndIncrementsCount() {
        val post = fixturePost(commentCount = 7L)

        val updated = post.addComment("게스트 여행자", "다음 여행에 저장해둘게요.")

        assertEquals(8L, updated.commentCount)
        assertEquals(1, updated.comments.size)
        assertEquals("게스트 여행자", updated.comments.last().authorName)
    }

    private fun fixturePost(
        likeCount: Long = 0L,
        commentCount: Long = 0L,
    ): DiscoverFeedPost = DiscoverFeedPost(
        item = SocialFeedItem(
            photoId = 1L,
            tripId = 1L,
            ownerId = 1L,
            thumbnailKey = "fixture",
            thumbnailMedia = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.UNAVAILABLE,
                signedUrl = null,
            ),
            caption = "fixture",
            cellId = null,
            latitude = null,
            longitude = null,
            takenAt = null,
            likeCount = likeCount,
            likedByViewer = false,
            createdAt = null,
        ),
        authorName = "fixture",
        locationLabel = "fixture",
        commentCount = commentCount,
        comments = emptyList(),
        localImageRes = 0,
    )
}
