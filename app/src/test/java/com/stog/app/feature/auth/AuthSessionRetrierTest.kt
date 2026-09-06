package com.stog.app.feature.auth

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AuthSessionRetrierTest {
    @Test
    fun retriesUnauthorizedRequestWithTheRotatedSession() {
        var storedTokens: StoredAuthTokens? = StoredAuthTokens("old-access", "old-refresh", 7L)
        val refreshed = StogAuthSession("new-access", "new-refresh", 7L, "여행자")
        var refreshCalls = 0
        var saveCalls = 0
        var callbackSession: StogAuthSession? = null
        val unauthorized = IOException("unauthorized")
        val retrier = AuthSessionRetrier(
            loadTokens = { storedTokens },
            refresh = { refreshToken ->
                refreshCalls++
                assertEquals("old-refresh", refreshToken)
                refreshed
            },
            saveSession = { session ->
                saveCalls++
                storedTokens = StoredAuthTokens(
                    session.accessToken,
                    session.refreshToken,
                    session.userId,
                )
            },
            onSessionRefreshed = { callbackSession = it },
        )
        var attempts = 0

        val result = retrier.execute(
            accessToken = "old-access",
            isAuthenticationFailure = { it === unauthorized },
        ) { token ->
            attempts++
            if (token == "old-access") throw unauthorized
            "loaded:$token"
        }

        assertEquals("loaded:new-access", result)
        assertEquals(2, attempts)
        assertEquals(1, refreshCalls)
        assertEquals(1, saveCalls)
        assertSame(refreshed, callbackSession)
        assertEquals("new-access", storedTokens?.accessToken)
    }

    @Test
    fun leavesNonAuthenticationFailuresUntouched() {
        val failure = IOException("offline")
        var refreshCalls = 0
        val retrier = AuthSessionRetrier(
            loadTokens = { StoredAuthTokens("access", "refresh", 7L) },
            refresh = {
                refreshCalls++
                error("must not refresh")
            },
            saveSession = {},
        )

        try {
            retrier.execute(
                accessToken = "access",
                isAuthenticationFailure = { false },
            ) { throw failure }
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        assertEquals(0, refreshCalls)
    }

    @Test
    fun notifiesExpirationWhenRefreshIsRejected() {
        val unauthorized = IOException("unauthorized")
        val refreshFailure = AuthRequestException(401, "AUTH_REFRESH_FAILED")
        var expirationCallbacks = 0
        val retrier = AuthSessionRetrier(
            loadTokens = { StoredAuthTokens("access", "refresh", 7L) },
            refresh = { throw refreshFailure },
            saveSession = {},
            onSessionExpired = { expirationCallbacks++ },
        )

        try {
            retrier.execute(
                accessToken = "access",
                isAuthenticationFailure = { it === unauthorized },
            ) { throw unauthorized }
        } catch (actual: AuthRequestException) {
            assertSame(refreshFailure, actual)
        }

        assertEquals(1, expirationCallbacks)
    }
}
