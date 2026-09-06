package com.stog.app.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceDetailsParserTest {
    @Test
    fun parsesPlaceDetailsFieldsAndOptionalValues() {
        val details = parsePlaceDetails(
            """
            {
              "external_id": "ChIJ123",
              "name": "전주 카페",
              "formatted_address": "전주시 완산구",
              "latitude": 35.82,
              "longitude": 127.14,
              "types": ["cafe"],
              "rating": 4.6,
              "user_rating_count": 128,
              "business_status": "OPERATIONAL",
              "open_now": true,
              "next_close_time": "2026-08-24T23:00:00+09:00",
              "regular_opening_hours": ["월요일: 오전 9:00~오후 9:00"],
              "national_phone_number": "063-123-4567",
              "website_uri": "https://example.com",
              "google_maps_uri": "https://maps.google.com/?cid=123",
              "photo_names": ["places/ChIJ123/photos/1"]
            }
            """.trimIndent(),
        )

        assertEquals("ChIJ123", details.externalId)
        assertEquals("전주 카페", details.name)
        assertEquals(4.6, details.rating!!, 0.001)
        assertEquals(128, details.userRatingCount)
        assertEquals("OPERATIONAL", details.businessStatus)
        assertEquals(true, details.openNow)
        assertEquals("2026-08-24T23:00:00+09:00", details.nextCloseTime)
        assertEquals(listOf("월요일: 오전 9:00~오후 9:00"), details.regularOpeningHours)
        assertEquals("063-123-4567", details.nationalPhoneNumber)
        assertEquals("https://example.com", details.websiteUri)
        assertEquals(listOf("places/ChIJ123/photos/1"), details.photoNames)
    }

    @Test
    fun missingOptionalDetailsRemainNullOrEmpty() {
        val details = parsePlaceDetails(
            """
            {
              "external_id": "ChIJ123",
              "name": "장소",
              "formatted_address": null,
              "national_phone_number": null,
              "website_uri": null,
              "rating": null,
              "user_rating_count": null,
              "business_status": null,
              "open_now": null,
              "next_close_time": null
            }
            """.trimIndent(),
        )

        assertNull(details.address)
        assertNull(details.nationalPhoneNumber)
        assertNull(details.websiteUri)
        assertNull(details.rating)
        assertNull(details.userRatingCount)
        assertNull(details.businessStatus)
        assertNull(details.openNow)
        assertNull(details.nextCloseTime)
        assertEquals(emptyList<String>(), details.regularOpeningHours)
        assertEquals(emptyList<String>(), details.photoNames)
    }
}
