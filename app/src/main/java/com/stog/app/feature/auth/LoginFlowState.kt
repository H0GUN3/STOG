package com.stog.app.feature.auth

enum class LoginDestination {
    LOGIN,
    HOME,
}

enum class LoginProvider {
    KAKAO,
    NAVER,
    GOOGLE,
}

enum class LoginButtonState {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR,
}

fun loginButtonState(
    provider: LoginProvider,
    loadingProvider: LoginProvider?,
    errorProvider: LoginProvider?,
    completedProvider: LoginProvider? = null,
): LoginButtonState = when {
    completedProvider == provider -> LoginButtonState.SUCCESS
    loadingProvider == provider -> LoginButtonState.LOADING
    errorProvider == provider -> LoginButtonState.ERROR
    else -> LoginButtonState.IDLE
}

fun destinationAfterSocialLogin(exchangeSucceeded: Boolean): LoginDestination =
    if (exchangeSucceeded) LoginDestination.HOME else LoginDestination.LOGIN
