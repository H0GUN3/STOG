package com.stog.app.feature.space

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TripCoverContractTest {
    @Test
    fun homeTripParsesRepresentativeImageUrl() {
        val home = PlanningApiClient("").parseHomeSummary(
            """
            {
              "monthly_trip_count": 1,
              "visited_cell_count": 0,
              "saved_place_count": 0,
              "monthly_received_like_count": 0,
              "trips": [{
                "id": 7,
                "title": "전주 여행",
                "activity_type": "tour",
                "mode": "dormant",
                "visibility": "private",
                "planned_start_date": "2026-09-01",
                "planned_end_date": "2026-09-03",
                "cover_image_url": "https://cdn.example/cover.jpg"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://cdn.example/cover.jpg",
            home.trips.single().coverImageUrl,
        )
    }

    @Test
    fun missingRepresentativeImageUsesPlaceholderState() {
        val home = PlanningApiClient("").parseHomeSummary(
            """
            {
              "monthly_trip_count": 0,
              "visited_cell_count": 0,
              "saved_place_count": 0,
              "monthly_received_like_count": 0,
              "trips": []
            }
            """.trimIndent(),
        )

        assertNull(home.trips.firstOrNull()?.coverImageUrl)
    }

    @Test
    fun nullRepresentativeImageIsParsedAsNull() {
        val home = PlanningApiClient("").parseHomeSummary(
            """
            {
              "monthly_trip_count": 1,
              "visited_cell_count": 0,
              "saved_place_count": 0,
              "monthly_received_like_count": 0,
              "trips": [{
                "id": 7,
                "title": "전주 여행",
                "activity_type": "tour",
                "mode": "dormant",
                "visibility": "private",
                "planned_start_date": null,
                "planned_end_date": null,
                "cover_image_url": null
              }]
            }
            """.trimIndent(),
        )

        assertNull(home.trips.single().coverImageUrl)
    }
}
