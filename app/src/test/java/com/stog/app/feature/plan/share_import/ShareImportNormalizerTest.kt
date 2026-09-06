package com.stog.app.feature.plan.share_import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareImportNormalizerTest {
    @Test
    fun kakao_map_share_extracts_title_address_and_url() {
        val payload = ShareImportPayload(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            texts = listOf(
                "[카카오맵] 전주 카페",
                "전북 전주시 완산구 전라감영로 10",
                "https://kko.to/example",
            ),
            htmlText = null,
            dataUri = null,
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )

        val result = ShareImportNormalizer.normalize(payload)

        assertEquals(ShareSource.KAKAO, result.source)
        assertEquals("전주 카페", result.title)
        assertEquals("전북 전주시 완산구 전라감영로 10", result.address)
        assertEquals("https://kko.to/example", result.originalUrl)
        assertEquals(listOf(PlaceMention("전주 카페", "전북 전주시 완산구 전라감영로 10")), result.mentions)
    }

    @Test
    fun instagram_share_keeps_multiple_text_mentions_as_unconfirmed_candidates() {
        val payload = ShareImportPayload(
            action = "android.intent.action.SEND",
            mimeType = "text/plain",
            texts = listOf(
                "전주 여행 추천",
                "객사 카페",
                "웨리단길 식당",
                "https://www.instagram.com/p/example",
            ),
            htmlText = null,
            dataUri = null,
            streamUris = listOf("content://media/1"),
            clipDataUris = listOf("content://media/1", "content://media/2"),
        )

        val result = ShareImportNormalizer.normalize(payload)

        assertEquals(ShareSource.INSTAGRAM, result.source)
        assertEquals("전주 여행 추천", result.title)
        assertEquals(null, result.address)
        assertEquals(
            listOf(
                PlaceMention("전주 여행 추천", null),
                PlaceMention("객사 카페", null),
                PlaceMention("웨리단길 식당", null),
            ),
            result.mentions,
        )
        assertEquals(listOf("content://media/1", "content://media/2"), result.attachmentUris)
    }

    @Test
    fun image_only_share_keeps_original_without_inventing_a_place() {
        val payload = ShareImportPayload(
            action = "android.intent.action.SEND",
            mimeType = "image/jpeg",
            texts = emptyList(),
            htmlText = null,
            dataUri = null,
            streamUris = listOf("content://media/3"),
            clipDataUris = emptyList(),
        )

        val result = ShareImportNormalizer.normalize(payload)

        assertEquals(ShareSource.UNKNOWN, result.source)
        assertNull(result.title)
        assertNull(result.address)
        assertEquals(emptyList<PlaceMention>(), result.mentions)
        assertEquals(listOf("content://media/3"), result.attachmentUris)
    }

    @Test
    fun inline_url_is_not_saved_as_part_of_the_place_title() {
        val result = ShareImportNormalizer.normalize(
            ShareImportPayload(
                action = "android.intent.action.SEND",
                mimeType = "text/plain",
                texts = listOf("[카카오맵] 전주 카페 https://kko.to/example"),
                htmlText = null,
                dataUri = null,
                streamUris = emptyList(),
                clipDataUris = emptyList(),
            ),
        )

        assertEquals("전주 카페", result.title)
        assertEquals("https://kko.to/example", result.originalUrl)
    }
}
