package com.stog.app.feature.plan.trip

import com.stog.app.R
import com.stog.app.feature.space.TripSummary
import com.stog.app.feature.space.defaultTripImageResource
import com.stog.app.feature.space.tripImageResource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TripCardDataTest {
    @Test
    fun realCardKeepsPersistedTripAndDerivedCardMetadata() {
        val trip = TripSummary(
            id = 77L,
            title = "새 제주 여행",
            activityType = "tour",
            mode = "dormant",
            visibility = "private",
            plannedStartDate = "2026-08-22",
            plannedEndDate = "2026-08-24",
        )

        val card = tripCardData(
            trip = trip,
            imageRes = 0,
            memberLabel = "나 · 동행자",
            placeCount = 3,
        )

        assertEquals(trip, card.trip)
        assertEquals("8/22 (토) - 8/24 (월) · 2박 3일", card.dateLabel)
        assertEquals("나 · 동행자", card.memberLabel)
        assertEquals(3, card.placeCount)
    }

    @Test
    fun categoryFiltersUsePlannedDates() {
        val today = LocalDate.of(2026, 8, 24)
        val cards = listOf(
            tripCardData(
                trip(
                    mode = "dormant",
                    plannedStartDate = "2026-08-22",
                    plannedEndDate = "2026-08-24",
                ),
                imageRes = 0,
            ),
            tripCardData(
                trip(
                    mode = "active",
                    plannedStartDate = "2026-08-25",
                    plannedEndDate = "2026-08-26",
                ),
                imageRes = 0,
            ),
            tripCardData(
                trip(
                    mode = "active",
                    plannedStartDate = "2026-08-20",
                    plannedEndDate = "2026-08-23",
                ),
                imageRes = 0,
            ),
            tripCardData(
                trip(
                    mode = "ended",
                    plannedStartDate = "2026-08-24",
                    plannedEndDate = "2026-08-24",
                ),
                imageRes = 0,
            ),
        )

        assertEquals(2, tripCardsFor(TripListCategory.ACTIVE, cards, today).size)
        assertEquals(1, tripCardsFor(TripListCategory.UPCOMING, cards, today).size)
        assertEquals(1, tripCardsFor(TripListCategory.COMPLETED, cards, today).size)
        assertEquals(4, tripCardsFor(TripListCategory.ALL, cards, today).size)
    }

    @Test
    fun tripWithoutDatesIsNotGuessedIntoADateCategory() {
        val card = tripCardData(
            trip(
                mode = "active",
                plannedStartDate = null,
                plannedEndDate = null,
            ),
            imageRes = 0,
        )

        assertEquals(
            0,
            tripCardsFor(TripListCategory.ACTIVE, listOf(card), LocalDate.of(2026, 8, 24)).size,
        )
        assertEquals(
            1,
            tripCardsFor(TripListCategory.ALL, listOf(card), LocalDate.of(2026, 8, 24)).size,
        )
    }

    @Test
    fun tripWithoutRepresentativePhotoUsesDefaultArtwork() {
        assertEquals(R.drawable.stog_travel_alley, defaultTripImageResource())
        assertEquals(R.drawable.stog_travel_alley, tripImageResource("새로운 여행"))
    }

    private fun trip(
        mode: String,
        plannedStartDate: String?,
        plannedEndDate: String?,
    ) = TripSummary(
        id = "$mode-$plannedStartDate-$plannedEndDate".hashCode().toLong(),
        title = mode,
        activityType = "tour",
        mode = mode,
        visibility = "private",
        plannedStartDate = plannedStartDate,
        plannedEndDate = plannedEndDate,
    )
}
