package com.stog.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginFlowStateTest {
    @Test
    fun successfulSocialExchangeMovesFromLoginToHome() {
        assertEquals(
            LoginDestination.HOME,
            destinationAfterSocialLogin(exchangeSucceeded = true),
        )
    }

    @Test
    fun failedSocialExchangeKeepsLoginVisible() {
        assertEquals(
            LoginDestination.LOGIN,
            destinationAfterSocialLogin(exchangeSucceeded = false),
        )
    }

    @Test
    fun loadingAndErrorBelongOnlyToTheirProvider() {
        assertEquals(
            LoginButtonState.LOADING,
            loginButtonState(
                provider = LoginProvider.KAKAO,
                loadingProvider = LoginProvider.KAKAO,
                errorProvider = null,
            ),
        )
        assertEquals(
            LoginButtonState.IDLE,
            loginButtonState(
                provider = LoginProvider.GOOGLE,
                loadingProvider = LoginProvider.KAKAO,
                errorProvider = null,
            ),
        )
        assertEquals(
            LoginButtonState.ERROR,
            loginButtonState(
                provider = LoginProvider.KAKAO,
                loadingProvider = null,
                errorProvider = LoginProvider.KAKAO,
            ),
        )
    }
}
