package com.peersplit.app.ui.theme

import androidx.compose.ui.graphics.Color

val Emerald80 = Color(0xFFA7F3D0)
val EmeraldGrey80 = Color(0xFFCCE8DE)
val Mint80 = Color(0xFFBBF7D0)

val Emerald40 = Color(0xFF059669)
val EmeraldDark = Color(0xFF047857)
val EmeraldGrey40 = Color(0xFF335C4D)
val Mint40 = Color(0xFF10B981)

val SurfaceDark = Color(0xFF121816)
val BackgroundDark = Color(0xFF0D1110)
val CardDark = Color(0xFF1A2220)

val SurfaceLight = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFF8FAFC)
val CardLight = Color(0xFFF1F5F9)

val BalancePositiveDark = Color(0xFF10B981)
val BalanceNegativeDark = Color(0xFFEF4444)
val BalancePositiveLight = Color(0xFF047857) // WCAG AA 4.7:1 on light backgrounds
val BalanceNegativeLight = Color(0xFFB91C1C) // WCAG AA 5.6:1 on light backgrounds
val BalanceNeutral = Color(0xFF94A3B8)
val BlockchainAccent = Color(0xFF6366F1)

val BalancePositive: Color
    @androidx.compose.runtime.Composable
    get() = if (androidx.compose.foundation.isSystemInDarkTheme()) BalancePositiveDark else BalancePositiveLight

val BalanceNegative: Color
    @androidx.compose.runtime.Composable
    get() = if (androidx.compose.foundation.isSystemInDarkTheme()) BalanceNegativeDark else BalanceNegativeLight
