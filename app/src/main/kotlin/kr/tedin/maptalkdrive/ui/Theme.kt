package kr.tedin.maptalkdrive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Colour roles. Text always wears an ink role; status colours belong to marks only and never carry meaning alone
 * (every status mark has a shape and a word). Day/night follows the system, as the Pleos shell does.
 */
data class Tokens(
    val page: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val onStatus: Color,
    val grid: Color,
    val axis: Color,
    val hairline: Color,
    val trackAhead: Color,
    val trackPassed: Color,
    val trackToNext: Color,
    val position: Color,
    val positionRing: Color,
    val actionBg: Color,
    val actionInk: Color,
    /** The band under a tunnel stretch: quieter than any road class so the curvature line stays readable through it. */
    val tunnel: Color,
) {
    /** "Somewhat different from usual": circle. */
    val some = Color(0xFFFAB219)
    /** "Clearly different from usual": triangle with "!". Red (critical) is reserved for immediate danger, unused. */
    val high = Color(0xFFEC835A)
    val good = Color(0xFF0CA30C)
}

val DarkTokens = Tokens(
    page = Color(0xFF0D0D0D), surface = Color(0xFF1A1A19),
    ink = Color(0xFFF0EFEC), ink2 = Color(0xFFC3C2B7), muted = Color(0xFF898781), onStatus = Color(0xFF1A1A19),
    grid = Color(0xFF2C2C2A), axis = Color(0xFF383835), hairline = Color(0x1AFFFFFF),
    trackAhead = Color(0xFF4A4945), trackPassed = Color(0xFF2C2C2A), trackToNext = Color(0xFFC3C2B7),
    position = Color(0xFFF0EFEC), positionRing = Color(0xFF1A1A19),
    actionBg = Color(0xFFF0EFEC), actionInk = Color(0xFF1A1A19),
    tunnel = Color(0xFF2A2A28),
)

val LightTokens = Tokens(
    page = Color(0xFFF7F7F7), surface = Color(0xFFFCFCFB),
    ink = Color(0xFF0B0B0B), ink2 = Color(0xFF52514E), muted = Color(0xFF898781), onStatus = Color(0xFF0B0B0B),
    grid = Color(0xFFE1E0D9), axis = Color(0xFFC3C2B7), hairline = Color(0x1A0B0B0B),
    trackAhead = Color(0xFFB0AEA5), trackPassed = Color(0xFFE1E0D9), trackToNext = Color(0xFF52514E),
    position = Color(0xFF0B0B0B), positionRing = Color(0xFFFCFCFB),
    actionBg = Color(0xFF0B0B0B), actionInk = Color(0xFFFCFCFB),
    tunnel = Color(0xFFE9E8E2),
)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** One sans, two weights (400 and 600). Sizes in sp; the smallest reading size in the car is 20sp (legal line 16sp). */
object Type {
    private val regular = FontWeight.Normal
    private val semi = FontWeight.SemiBold
    val driveDistance = TextStyle(fontSize = 112.sp, lineHeight = 112.sp, fontWeight = semi, letterSpacing = (-0.01).em)
    val driveIssue = TextStyle(fontSize = 56.sp, lineHeight = 68.sp, fontWeight = semi)
    val driveUnit = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = regular)
    val driveMeta = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = regular)
    val headline = TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = semi, letterSpacing = (-0.01).em)
    val context = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = regular)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = semi)
    val body = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = regular)
    val bodyNum = body.copy(fontFeatureSettings = "tnum")
    val caption = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = regular)
    val captionNum = caption.copy(fontFeatureSettings = "tnum")
    val title = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = semi)
    val button = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = semi)
    val legal = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = regular)
}

@Composable
fun MaptalkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(primary = t.actionBg, onPrimary = t.actionInk, background = t.surface, surface = t.surface, onSurface = t.ink, outline = t.hairline, onSurfaceVariant = t.ink2)
    } else {
        lightColorScheme(primary = t.actionBg, onPrimary = t.actionInk, background = t.surface, surface = t.surface, onSurface = t.ink, outline = t.hairline, onSurfaceVariant = t.ink2)
    }
    val typography = Typography(labelLarge = Type.button, bodyLarge = Type.body, titleMedium = Type.title)
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
