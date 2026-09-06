package com.stog.app.feature.social

import com.stog.app.R

internal data class DiscoverComment(
    val authorName: String,
    val body: String,
)

internal data class DiscoverFeedPost(
    val item: SocialFeedItem,
    val authorName: String,
    val locationLabel: String,
    val commentCount: Long,
    val comments: List<DiscoverComment>,
    val localImageRes: Int,
    val savedByViewer: Boolean = false,
)

internal fun fixtureDiscoverFeedPosts(): List<DiscoverFeedPost> = listOf(
    DiscoverFeedPost(
        item = SocialFeedItem(
            photoId = 9001L,
            tripId = 900L,
            ownerId = 101L,
            thumbnailKey = "fixture/discover/hanok-sunset",
            thumbnailMedia = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.UNAVAILABLE,
                signedUrl = null,
            ),
            caption = "전주한옥마을의 어느새 따뜻해진 노을. 사람도, 풍경도, 다 좋았던 하루",
            cellId = null,
            latitude = null,
            longitude = null,
            takenAt = "2026-08-20T18:40:00Z",
            likeCount = 243L,
            likedByViewer = false,
            createdAt = "2026-08-20T19:00:00Z",
        ),
        authorName = "seo._travel",
        locationLabel = "전라북도 전주시",
        commentCount = 18L,
        comments = listOf(
            DiscoverComment("minji_trip", "노을 색감이 정말 예뻐요."),
        ),
        localImageRes = R.drawable.stog_discover_feed_jeonju,
    ),
    DiscoverFeedPost(
        item = SocialFeedItem(
            photoId = 9002L,
            tripId = 901L,
            ownerId = 102L,
            thumbnailKey = "fixture/discover/coast-trail",
            thumbnailMedia = SocialThumbnailMedia(
                status = SocialThumbnailMediaStatus.UNAVAILABLE,
                signedUrl = null,
            ),
            caption = "바다를 따라 천천히 걷는 날. 다음 여행의 속도도 이 정도면 좋겠어요.",
            cellId = null,
            latitude = null,
            longitude = null,
            takenAt = "2026-08-18T16:20:00Z",
            likeCount = 96L,
            likedByViewer = false,
            createdAt = "2026-08-18T17:00:00Z",
        ),
        authorName = "minji_trip",
        locationLabel = "부산 근교 해안",
        commentCount = 7L,
        comments = listOf(
            DiscoverComment("seo._travel", "저장해두고 싶은 산책길이에요."),
        ),
        localImageRes = R.drawable.stog_discover_feed_coast,
    ),
)

internal fun DiscoverFeedPost.toggleLike(): DiscoverFeedPost {
    val liked = !item.likedByViewer
    return copy(
        item = item.copy(
            likedByViewer = liked,
            likeCount = (item.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
        ),
    )
}

internal fun DiscoverFeedPost.addComment(
    authorName: String,
    body: String,
): DiscoverFeedPost = copy(
    commentCount = commentCount + 1,
    comments = comments + DiscoverComment(authorName, body),
)
