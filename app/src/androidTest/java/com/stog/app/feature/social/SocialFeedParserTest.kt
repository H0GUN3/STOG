package com.stog.app.feature.social

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialFeedParserTest {
    @Test
    fun parsesPageAndPreservesCursorAndLikeState() {
        val page = parseSocialFeedPage(
            JSONObject(
                """
                {
                  "items": [{
                    "photo_id": 7,
                    "trip_id": 9,
                    "owner_id": 3,
                    "thumbnail_key": "photos/3/thumb.jpg",
                    "caption": "전주 기록",
                    "cell_id": "8a2a1072b59ffff",
                    "lat": 35.82,
                    "lng": 127.15,
                    "taken_at": "2026-08-21T02:00:00Z",
                    "like_count": 4,
                    "liked_by_viewer": true,
                    "created_at": "2026-08-21T02:01:00Z"
                  }],
                  "next_cursor": "cursor-2"
                }
                """.trimIndent(),
            ),
        )

        assertEquals("cursor-2", page.nextCursor)
        assertEquals(7L, page.items.single().photoId)
        assertEquals("photos/3/thumb.jpg", page.items.single().thumbnailKey)
        assertTrue(page.items.single().likedByViewer)
        assertEquals(4L, page.items.single().likeCount)
    }
}
