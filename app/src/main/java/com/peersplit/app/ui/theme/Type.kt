package com.peersplit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val FintechSans = FontFamily.SansSerif
private const val TABULAR_NUMS = "tnum"

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    displayMedium = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.75).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    displaySmall = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    headlineLarge = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    headlineMedium = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    headlineSmall = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    titleLarge = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    titleMedium = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    titleSmall = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodyLarge = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodyMedium = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    bodySmall = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelLarge = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelMedium = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    labelSmall = TextStyle(
        fontFamily = FintechSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
        fontFeatureSettings = TABULAR_NUMS
    )
)

