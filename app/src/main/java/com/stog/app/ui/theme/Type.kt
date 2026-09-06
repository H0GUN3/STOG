package com.stog.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stog.app.R

val PretendardFontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private val MaterialDefaults = Typography()

private fun TextStyle.withPretendard(weight: FontWeight): TextStyle =
    copy(
        fontFamily = PretendardFontFamily,
        fontWeight = weight,
    )

val Typography = Typography(
    displayLarge = MaterialDefaults.displayLarge.withPretendard(FontWeight.Bold),
    displayMedium = MaterialDefaults.displayMedium.withPretendard(FontWeight.Bold),
    displaySmall = MaterialDefaults.displaySmall.withPretendard(FontWeight.Bold),
    headlineLarge = MaterialDefaults.headlineLarge.withPretendard(FontWeight.Bold).copy(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = MaterialDefaults.headlineMedium.withPretendard(FontWeight.Bold).copy(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = MaterialDefaults.headlineSmall.withPretendard(FontWeight.Bold),
    titleLarge = MaterialDefaults.titleLarge.withPretendard(FontWeight.SemiBold).copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = MaterialDefaults.titleMedium.withPretendard(FontWeight.SemiBold),
    titleSmall = MaterialDefaults.titleSmall.withPretendard(FontWeight.SemiBold),
    bodyLarge = MaterialDefaults.bodyLarge.withPretendard(FontWeight.Normal).copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = MaterialDefaults.bodyMedium.withPretendard(FontWeight.Normal).copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = MaterialDefaults.bodySmall.withPretendard(FontWeight.Normal),
    labelLarge = MaterialDefaults.labelLarge.withPretendard(FontWeight.SemiBold).copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = MaterialDefaults.labelMedium.withPretendard(FontWeight.Medium),
    labelSmall = MaterialDefaults.labelSmall.withPretendard(FontWeight.Medium),
)
