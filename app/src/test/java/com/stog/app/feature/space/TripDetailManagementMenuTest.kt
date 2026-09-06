package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetailManagementMenuTest {
    @Test
    fun ownerSeesOnlyOwnerActions() {
        val actions = tripManagementActions(isOwner = true)

        assertEquals(
            listOf(
                TripManagementAction.SHARE_INVITE,
                TripManagementAction.MANAGE_MEMBERS,
                TripManagementAction.EDIT_TRIP,
                TripManagementAction.DELETE_TRIP,
            ),
            actions,
        )
        assertFalse(actions.contains(TripManagementAction.LEAVE_TRIP))
    }

    @Test
    fun memberSeesViewAndLeaveActionsWithoutDelete() {
        val actions = tripManagementActions(isOwner = false)

        assertEquals(
            listOf(
                TripManagementAction.SHARE_INVITE,
                TripManagementAction.VIEW_MEMBERS,
                TripManagementAction.LEAVE_TRIP,
            ),
            actions,
        )
        assertTrue(!actions.contains(TripManagementAction.DELETE_TRIP))
        assertTrue(!actions.contains(TripManagementAction.EDIT_TRIP))
    }
}
