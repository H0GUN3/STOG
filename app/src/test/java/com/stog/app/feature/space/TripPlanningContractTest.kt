package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlanningContractTest {
    @Test
    fun placeCategoryUsesOneHumanizedNormalizedValue() {
        assertEquals("카페", humanizedPlaceCategory(listOf("cafe", "food", "point_of_interest")))
        assertEquals("음식점", humanizedPlaceCategory(listOf("restaurant")))
        assertEquals("장소", humanizedPlaceCategory(emptyList()))
    }

    @Test
    fun basketParserKeepsPlaceAndConfirmedLinkItems() {
        val items = basketItemsFromResponses(
            listOf(
                BasketItemResponse(42, "place", "전주 카페", "cafe", "google", "resolved", "2026-08-19T12:00:00Z"),
                BasketItemResponse(43, "link", "공유로 담은 장소", null, "naver", "unresolved", "2026-08-19T12:01:00Z"),
            ),
        )

        assertEquals(
            listOf(
                BasketItem(42, "place", "전주 카페", "cafe", "google", "resolved", "2026-08-19T12:00:00Z"),
                BasketItem(43, "link", "공유로 담은 장소", null, "naver", "unresolved", "2026-08-19T12:01:00Z"),
            ),
            items,
        )
    }

    @Test
    fun itineraryStateAddsRemovesAndReordersDayOneBasketItems() {
        val first = ItineraryItem(42, 1, 0, null, null)
        val second = ItineraryItem(43, 1, 1, null, null)
        val withSecond = addBasketItemToDayOne(listOf(first), 43)

        assertEquals(listOf(first, second), withSecond)
        assertEquals(
            listOf(
                ItineraryItem(43, 1, 0, null, null),
                ItineraryItem(42, 1, 1, null, null),
            ),
            moveDayOneItem(withSecond, 43, -1),
        )
        assertEquals(emptyList<ItineraryItem>(), removeItineraryBasketItem(listOf(first), 42))
    }

    @Test
    fun itineraryStateAssignsItemsToTheSelectedDay() {
        val dayOne = ItineraryItem(42, 1, 0, null, null)
        val dayTwo = addBasketItemToDay(listOf(dayOne), 43, 2)
        val dayTwoSecond = addBasketItemToDay(dayTwo, 44, 2)

        assertEquals(2, dayTwo.single { it.basketItemId == 43L }.dayNumber)
        assertEquals(
            listOf(
                dayOne,
                ItineraryItem(44, 2, 0, null, null),
                ItineraryItem(43, 2, 1, null, null),
            ),
            moveDayItem(dayTwoSecond, 44, 2, -1),
        )
    }

    @Test
    fun itineraryParserAndPayloadKeepDayOrderAndFixedState() {
        val items = itineraryItemsFromResponses(
            listOf(ItineraryItemResponse(42, 1, 0, null, null, fixed = true)),
        )

        assertEquals(
            listOf(ItineraryItem(42, 1, 0, null, null, fixed = true)),
            items,
        )
        assertEquals(
            "{\"items\":[{\"basket_item_id\":42,\"day_number\":1,\"order_index\":0,\"planned_arrival\":null,\"planned_duration_min\":null,\"is_fixed\":true}]}",
            itineraryRequestBody(items),
        )
    }

    @Test
    fun pendingBookmarkResumesOnlyAfterAuthentication() {
        val candidate = PlaceSearchCandidate(
            externalId = "google-place",
            name = "전주 카페",
            address = "전북 전주시",
            latitude = 35.8,
            longitude = 127.1,
        )

        assertFalse(shouldResumeBookmark(candidate, null))
        assertTrue(shouldResumeBookmark(candidate, "token"))
    }

    @Test
    fun placeSearchIsNotOwnedByTheMapBottomSheet() {
        assertFalse(MapSheetKind.entries.any { it.name == "PLACE_SEARCH" })
    }
}
