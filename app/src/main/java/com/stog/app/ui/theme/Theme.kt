package com.stog.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val StogDarkColorScheme = darkColorScheme(
    primary = StogYellow,
    onPrimary = StogInk,
    background = StogDarkCanvas,
    onBackground = StogCanvas,
    surface = StogDarkSurface,
    onSurface = StogCanvas,
)

private val StogLightColorScheme = lightColorScheme(
    primary = StogYellow,
    onPrimary = StogInk,
    primaryContainer = StogYellow,
    onPrimaryContainer = StogInk,
    secondary = StogInk,
    onSecondary = StogCanvas,
    background = StogCanvas,
    onBackground = StogInk,
    surface = StogSurface,
    onSurface = StogInk,
    surfaceVariant = StogSurface,
    onSurfaceVariant = StogMuted,
    outline = StogBorder,
)

@Composable
fun STOGTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        darkTheme -> StogDarkColorScheme
        else -> StogLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
