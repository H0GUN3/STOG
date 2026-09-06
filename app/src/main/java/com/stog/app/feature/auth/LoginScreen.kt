package com.stog.app.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.ui.StogLogo
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogKakaoYellow
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogGoogleInk
import com.stog.app.ui.theme.StogGoogleSurface

@Composable
fun LoginScreen(
    message: String?,
    isLoading: Boolean,
    onKakaoLogin: () -> Unit,
    onGoogleLogin: () -> Unit,
    onGuest: () -> Unit,
    modifier: Modifier = Modifier,
    loadingProvider: LoginProvider? = null,
    errorProvider: LoginProvider? = null,
    onNaverLogin: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StogCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StogUiContract.ScreenGutterDp.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(144.dp))
        StogLogo(modifier = Modifier.size(96.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "여행의 모든 순간을 기록하다",
            color = StogInk,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(72.dp))
        LoginProviderButton(
            label = "카카오로 시작하기",
            state = loginButtonState(
                provider = LoginProvider.KAKAO,
                loadingProvider = loadingProvider,
                errorProvider = errorProvider,
            ),
            loadingLabel = "카카오 로그인 확인 중...",
            containerColor = StogKakaoYellow,
            contentColor = Color.Black.copy(alpha = 0.85f),
            enabled = !isLoading,
            preserveDisabledAppearance = isLoading,
            onClick = onKakaoLogin,
        ) {
            KakaoProviderIcon()
        }
        message
            ?.takeIf { errorProvider == LoginProvider.KAKAO }
            ?.let {
                LoginInlineMessage(
                    message = it,
                    isError = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        Spacer(modifier = Modifier.height(16.dp))
        LoginProviderButton(
            label = "네이버로 시작하기",
            state = loginButtonState(
                provider = LoginProvider.NAVER,
                loadingProvider = loadingProvider,
                errorProvider = errorProvider,
            ),
            loadingLabel = "네이버 로그인 확인 중...",
            containerColor = Color(0xFF03C75A),
            contentColor = Color.White,
            enabled = !isLoading,
            preserveDisabledAppearance = isLoading,
            onClick = onNaverLogin,
        ) {
            ProviderIconImage(
                imageRes = R.drawable.naver_login_transparent,
            )
        }
        message
            ?.takeIf { errorProvider == LoginProvider.NAVER }
            ?.let {
                LoginInlineMessage(
                    message = it,
                    isError = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        Spacer(modifier = Modifier.height(16.dp))
        LoginProviderButton(
            label = "구글로 시작하기",
            state = loginButtonState(
                provider = LoginProvider.GOOGLE,
                loadingProvider = loadingProvider,
                errorProvider = errorProvider,
            ),
            loadingLabel = "구글 로그인 확인 중...",
            containerColor = StogGoogleSurface,
            contentColor = StogGoogleInk,
            enabled = !isLoading,
            preserveDisabledAppearance = isLoading,
            onClick = onGoogleLogin,
            borderColor = StogBorder,
        ) {
            ProviderIconImage(R.drawable.google_sign_in_official)
        }
        message
            ?.takeIf { errorProvider == LoginProvider.GOOGLE }
            ?.let {
                LoginInlineMessage(
                    message = it,
                    isError = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onGuest,
            enabled = !isLoading,
            modifier = Modifier.stogTouchTarget(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "로그인 없이 둘러보기",
                color = StogMuted,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.Underline,
            )
        }
        if (!isLoading && errorProvider == null) {
            message?.let {
                LoginInlineMessage(
                    message = it,
                    isError = false,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun LoginProviderButton(
    label: String,
    state: LoginButtonState,
    loadingLabel: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    preserveDisabledAppearance: Boolean,
    onClick: () -> Unit,
    borderColor: Color? = null,
    icon: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = if (preserveDisabledAppearance) {
                containerColor
            } else {
                containerColor.copy(alpha = 0.55f)
            },
            disabledContentColor = if (preserveDisabledAppearance) {
                contentColor
            } else {
                contentColor.copy(alpha = 0.55f)
            },
        ),
        border = borderColor?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state == LoginButtonState.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = contentColor,
                        strokeWidth = 2.dp,
                    )
                } else {
                    icon()
                }
            }
            Text(
                text = if (state == LoginButtonState.LOADING) loadingLabel else label,
                style = MaterialTheme.typography.labelLarge.copy(
                    lineHeight = MaterialTheme.typography.labelLarge.lineHeight,
                ),
            )
        }
    }
}

@Composable
private fun LoginInlineMessage(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        color = if (isError) MaterialTheme.colorScheme.error else StogMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun KakaoProviderIcon() {
    Image(
        painter = painterResource(R.drawable.kakao_logo),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(Color.Black),
    )
}

@Composable
private fun ProviderIconImage(
    imageRes: Int,
    size: Dp = 40.dp,
) {
    Image(
        painter = painterResource(imageRes),
        contentDescription = null,
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
