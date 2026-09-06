package com.stog.app.feature.social

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialThumbnailLoaderTest {
    @Test
    fun directLoaderAppliesBoundedConnectAndReadTimeoutsBeforeHttpFailure() = runBlocking {
        val connection = RecordingHttpConnection()

        val result = loadSocialThumbnail("https://storage.test/signed-thumbnail") {
            connection
        }

        assertSame(SocialThumbnailState.Failed, result)
        assertEquals(SOCIAL_NETWORK_TIMEOUT_MILLIS, connection.connectTimeout)
        assertEquals(SOCIAL_NETWORK_TIMEOUT_MILLIS, connection.readTimeout)
        assertTrue(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertTrue(SOCIAL_NETWORK_TIMEOUT_MILLIS in 1..15_000)
    }

    private class RecordingHttpConnection : HttpURLConnection(
        URL("https://storage.test/signed-thumbnail"),
    ) {
        var disconnected = false

        override fun getResponseCode(): Int = HTTP_UNAVAILABLE

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
