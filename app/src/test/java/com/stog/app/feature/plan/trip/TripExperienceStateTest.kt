package com.stog.app.feature.plan.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripExperienceStateTest {
    @Test
    fun productionHomeUsesHonestEmptyTripState() {
        assertTrue(HOME_TRIP_ITEMS.isEmpty())
    }

    @Test
    fun homePrefersActiveTripOverUpcomingAndCompletedTrips() {
        val selected = homeTrip(
            listOf(
                TripCardUi(3, "완료", TripStage.COMPLETED, "", ""),
                TripCardUi(2, "예정", TripStage.UPCOMING, "", ""),
                TripCardUi(1, "여행 중", TripStage.ACTIVE, "", ""),
            ),
        )

        assertEquals(1L, selected?.id)
    }

    @Test
    fun basketSelectionTogglesWithoutDuplicates() {
        val selected = toggleBasketSelection(setOf(10L), 10L)
        val selectedAgain = toggleBasketSelection(selected, 10L)

        assertFalse(10L in selected)
        assertTrue(10L in selectedAgain)
        assertEquals(1, selectedAgain.size)
    }

    @Test
    fun activeJourneyRetainsCompletedItemsInASeparateFold() {
        val items = listOf(
            ItineraryStopUi(1, "09:00", "시장", "관광지", "30분", completed = true),
            ItineraryStopUi(2, "11:00", "카페", "카페", "60분"),
            ItineraryStopUi(3, "14:00", "전시", "관광지", "90분", completed = true),
        )

        val journey = journeyItems(items)

        assertEquals(listOf(2L), journey.remaining.map(ItineraryStopUi::id))
        assertEquals(listOf(1L, 3L), journey.completed.map(ItineraryStopUi::id))
    }

    @Test
    fun lifecycleAndMemberLateEventFailuresHaveHonestLabels() {
        assertEquals("여행 기록 중", journeyStateLabel("active"))
        assertEquals("기록 대기 중", journeyStateLabel("dormant"))
        assertEquals("종료된 여행", journeyStateLabel("ended"))
        assertTrue(journeyFailureMessage(403).contains("참여자"))
        assertTrue(journeyFailureMessage(400, "VISIT_AFTER_TRIP_END").contains("종료 뒤"))
        assertTrue(journeyFailureMessage(400, "LATE_VISIT_GRACE_EXPIRED").contains("시간이 지났"))
    }
}
