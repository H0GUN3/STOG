package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ItineraryDetailTest {
    @Test
    fun addingAndRemovingScheduleItemsKeepsDayOrder() {
        val initial = listOf(
            ItineraryItem(1L, 1, 0, "09:00", 30, fixed = true),
            ItineraryItem(2L, 1, 1, "10:00", 30),
            ItineraryItem(3L, 2, 0, null, null),
        )

        val added = addItineraryItemToDay(initial, 4L, 1)
        val removed = removeItineraryItem(added, initial[1])

        assertEquals(listOf(1L, 4L), removed.filter { it.dayNumber == 1 }.map { it.basketItemId })
        assertEquals(listOf(0, 1), removed.filter { it.dayNumber == 1 }.map { it.orderIndex })
        assertEquals(true, removed.first { it.basketItemId == 1L }.fixed)
        assertSame(initial[2], removed.single { it.dayNumber == 2 })
    }
}
