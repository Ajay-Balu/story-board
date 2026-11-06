package com.ajay.storyboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import com.ajay.storyboard.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Hallmark theme "Almanac" (app scope): cool slate stock with a pale printed sheet, blue-black ink,
 * Hanken Grotesk heads over Newsreader prose, two rule weights, soft 6px corners, one deep magenta that
 * marks the primary action and the current selection only. Dark mode re-derives every token (same hues).
 */
private val Light = lightColorScheme(
    primary = Color(0xFF970D63),
    onPrimary = Color(0xFFF2F6F9),
    primaryContainer = Color(0xFFF2CBDC),
    onPrimaryContainer = Color(0xFF410228),
    inversePrimary = Color(0xFFE274AB),
    secondary = Color(0xFF21303C),
    onSecondary = Color(0xFFF2F6F9),
    secondaryContainer = Color(0xFFBAC6D1),
    onSecondaryContainer = Color(0xFF060E15),
    tertiary = Color(0xFF2B343B),
    onTertiary = Color(0xFFF2F6F9),
    tertiaryContainer = Color(0xFFCFD5DA),
    onTertiaryContainer = Color(0xFF060E15),
    background = Color(0xFFD5DCE1),
    onBackground = Color(0xFF182027),
    surface = Color(0xFFD5DCE1),
    onSurface = Color(0xFF182027),
    surfaceVariant = Color(0xFFCCD2D7),
    onSurfaceVariant = Color(0xFF3B434B),
    surfaceTint = Color(0xFFD5DCE1),
    inverseSurface = Color(0xFF182027),
    inverseOnSurface = Color(0xFFE5EAEF),
    error = Color(0xFFA52A24),
    onError = Color(0xFFF2F6F9),
    errorContainer = Color(0xFFF8D5CF),
    onErrorContainer = Color(0xFF410E0A),
    outline = Color(0xFF2B343B),
    outlineVariant = Color(0xFFA1A9AF),
    scrim = Color(0xFF060E15),
    surfaceBright = Color(0xFFECF3F8),
    surfaceDim = Color(0xFFC5CBD1),
    surfaceContainerLowest = Color(0xFFECF3F8),
    surfaceContainerLow = Color(0xFFDCE2E8),
    surfaceContainer = Color(0xFFCFD5DA),
    surfaceContainerHigh = Color(0xFFC5CBD1),
    surfaceContainerHighest = Color(0xFFBFC5CA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE274AB),
    onPrimary = Color(0xFF1B0C13),
    primaryContainer = Color(0xFF501F38),
    onPrimaryContainer = Color(0xFFF4D4E2),
    inversePrimary = Color(0xFF970D63),
    secondary = Color(0xFFBAC6D1),
    onSecondary = Color(0xFF0D1217),
    secondaryContainer = Color(0xFF232F3A),
    onSecondaryContainer = Color(0xFFE2E9F0),
    tertiary = Color(0xFF9EA6AD),
    onTertiary = Color(0xFF0D1217),
    tertiaryContainer = Color(0xFF22272C),
    onTertiaryContainer = Color(0xFFE2E9F0),
    background = Color(0xFF090E12),
    onBackground = Color(0xFFD9E0E7),
    surface = Color(0xFF090E12),
    onSurface = Color(0xFFD9E0E7),
    surfaceVariant = Color(0xFF1B2025),
    onSurfaceVariant = Color(0xFF9EA6AD),
    surfaceTint = Color(0xFF090E12),
    inverseSurface = Color(0xFFD9E0E7),
    inverseOnSurface = Color(0xFF12171B),
    error = Color(0xFFF08F83),
    onError = Color(0xFF2A0703),
    errorContainer = Color(0xFF4B1E19),
    onErrorContainer = Color(0xFFF8D5CF),
    outline = Color(0xFF7E888F),
    outlineVariant = Color(0xFF282F34),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1B2025),
    surfaceDim = Color(0xFF090E12),
    surfaceContainerLowest = Color(0xFF1B2025),
    surfaceContainerLow = Color(0xFF12171B),
    surfaceContainer = Color(0xFF161B20),
    surfaceContainerHigh = Color(0xFF1B2025),
    surfaceContainerHighest = Color(0xFF22272C),
)

/** Soft corners everywhere, never sharp: 6px on controls and chips, 8 to 12 on sheets and dialogs. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int, style: FontStyle = FontStyle.Normal) =
    Font(res, FontWeight(weight), style, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

object AppFonts {
    /** Heads, titles, buttons. */
    val grotesk = FontFamily(variable(R.font.hanken_grotesk, 500), variable(R.font.hanken_grotesk, 600), variable(R.font.hanken_grotesk, 700))
    /** Prose, labels and meta. */
    val serif = FontFamily(
        variable(R.font.newsreader, 400), variable(R.font.newsreader, 500), variable(R.font.newsreader, 600),
        variable(R.font.newsreader_italic, 400, FontStyle.Italic), variable(R.font.newsreader_italic, 500, FontStyle.Italic),
    )
    /** Prompts and figures only. */
    val mono = FontFamily(Font(R.font.plex_mono, FontWeight.Normal))
}

private fun head(size: Int, line: Int, weight: Int = 600, track: Double = -0.2) =
    TextStyle(fontFamily = AppFonts.grotesk, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp)
private fun prose(size: Double, line: Int, weight: Int = 400) =
    TextStyle(fontFamily = AppFonts.serif, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp)

/** A fixed scale near 1.15: grotesk heads and controls, serif prose and labels, never tracked caps. */
private val AppTypography = Typography(
    displayLarge = head(48, 52, track = -1.2),
    displayMedium = head(40, 44, track = -1.0),
    displaySmall = head(34, 38, track = -0.8),
    headlineLarge = head(30, 34, track = -0.6),
    headlineMedium = head(26, 30, track = -0.5),
    headlineSmall = head(23, 28, track = -0.4),
    titleLarge = head(20, 26, track = -0.3),
    titleMedium = head(17, 23, track = -0.2),
    titleSmall = head(15, 20, track = -0.1),
    bodyLarge = prose(17.0, 26),
    bodyMedium = prose(16.0, 24),
    bodySmall = prose(14.0, 20, 500),
    labelLarge = head(14, 20, 500, 0.0),
    labelMedium = prose(13.5, 18, 500),
    labelSmall = prose(12.5, 16, 500),
)

/** The one fixed reading style (no reader settings). */
val ReaderBody = TextStyle(fontFamily = AppFonts.serif, fontSize = 19.sp, lineHeight = 30.sp)
val ReaderHeading = TextStyle(fontFamily = AppFonts.grotesk, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.5).sp)

@Composable
fun StoryBoardTheme(
    mode: com.ajay.storyboard.data.ThemeMode = com.ajay.storyboard.data.ThemeMode.SYSTEM,
    wallpaperColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        com.ajay.storyboard.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        com.ajay.storyboard.data.ThemeMode.LIGHT -> false
        com.ajay.storyboard.data.ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
}
