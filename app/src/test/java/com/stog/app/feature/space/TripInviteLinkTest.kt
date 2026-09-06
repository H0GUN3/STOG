package com.stog.app.feature.space

import android.net.HostTestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripInviteLinkTest {
    @Test
    fun shareLinkUsesThePublicHttpsInviteContract() {
        assertEquals(
            "https://stog-backend-qoeu5cmuxq-as.a.run.app/trip-invites/Abc_-123",
            tripInviteShareLink("Abc_-123"),
        )
    }

    @Test
    fun parserAcceptsHttpsAndLegacyInviteLinks() {
        assertEquals(
            "Abc_-123",
            tripInviteTokenFromUri(HostTestUri.create("stog://trip-invites/Abc_-123")),
        )
        assertEquals(
            "Abc_-123",
            tripInviteTokenFromUri(
                HostTestUri.create(
                    "https://stog-backend-qoeu5cmuxq-as.a.run.app/trip-invites/Abc_-123",
                ),
            ),
        )
        assertNull(
            tripInviteTokenFromUri(
                HostTestUri.create("https://example.com/trip-invites/Abc_-123"),
            ),
        )
        assertNull(tripInviteTokenFromUri(HostTestUri.create("stog://other/Abc_-123")))
        assertNull(
            tripInviteTokenFromUri(HostTestUri.create("stog://trip-invites/Abc_-123/extra")),
        )
        assertNull(
            tripInviteTokenFromUri(
                HostTestUri.create(
                    "https://stog-backend-qoeu5cmuxq-as.a.run.app/trip-invites/Abc_-123/extra",
                ),
            ),
        )
        assertNull(tripInviteTokenFromUri(HostTestUri.create("stog://trip-invites/invalid.token")))
    }

    @Test
    fun unauthenticatedInviteWaitsForLogin() {
        assertEquals(TripInviteEntry.LOGIN, tripInviteEntryFor(null))
        assertEquals(TripInviteEntry.LOGIN, tripInviteEntryFor(""))
        assertEquals(TripInviteEntry.JOIN, tripInviteEntryFor("access-token"))
    }
}
