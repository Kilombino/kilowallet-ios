package com.kilombino.kilowallet.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The Android app's palette (ui/Theme.kt, 1.4.4): dark blue panels and text, and one colour per
// part of the wallet — BTC orange, Lightning gold, Kilojoin blue, Ark red.
val Ink = Color(0xFF000000)
val PanelBg = Color(0xFF050A12)
val PanelSoft = Color(0xFF08101C)
val Line = Color(0x2E278BF5)
val TextMain = Color(0xFFD0DCEC)
val TextSoft = Color(0xFF9CB0C8)
val TextFaint = Color(0xFF5C7090)
/** The general accent, BTC's orange (named for the app's first colour, as on Android). */
val Purple = Color(0xFFF7931A)
val Orange = Color(0xFFF7931A)
val Good = Color(0xFF34D399)
/** Lightning (its tab). */
val LnGold = Color(0xFFF5B83D)
/** Kilojoin (its tab). */
val PoolBlue = Color(0xFF278BF5)
/** Ark (its tab): a crimson apart from the error red. */
val ArkRed = Color(0xFFE5484D)
val Warn = Color(0xFFFFC53D)
val Bad = Color(0xFFFF5D5D)

private val mono = FontFamily.Monospace

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = mono, fontSize = 44.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = mono, fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = mono, fontSize = 18.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 11.sp),
    labelLarge = TextStyle(fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, letterSpacing = 1.5.sp),
)

@Composable
fun KilowalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple, background = Ink, surface = PanelBg,
            onPrimary = Ink, onBackground = TextMain, onSurface = TextMain,
            surfaceVariant = PanelSoft, onSurfaceVariant = TextSoft, outline = TextFaint,
            error = Bad,
        ),
        typography = typo,
        content = content,
    )
}
