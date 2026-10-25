package com.cinnamon.vantracker.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.R

/*
 * Design language: Apple-style layout (grouped inset cards, large titles, hairline
 * separators, tinted controls) dressed in the Cinnamon Hotels & Resorts palette –
 * warm cinnamon, gold and deep navy, with an elegant serif for titles.
 */

// ------------------------------------------------------------------ appearance preference

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

object ThemePrefs {
    private const val PREFS = "ui"
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun load(ctx: Context) {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", null)
        mode = ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.SYSTEM
    }

    fun set(ctx: Context, m: ThemeMode) {
        mode = m
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", m.name).apply()
    }
}

@Composable
fun isDarkTheme(): Boolean = when (ThemePrefs.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

// ------------------------------------------------------------------ palette

/** Status colours (iOS system tints – legible on light and dark backgrounds). */
val OkGreen = Color(0xFF34C759)
val WarnOrange = Color(0xFFFF9500)
val DangerRed = Color(0xFFFF3B30)
val InfoBlue = Color(0xFF0A84FF)

@Immutable
data class BrandColors(
    val accent: Color,        // cinnamon
    val gold: Color,
    val ink: Color,           // headings (navy in light mode)
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,    // grouped background
    val card: Color,
    val fill: Color,          // input / tinted fills
    val separator: Color,
    val dark: Boolean,
)

private val LightBrand = BrandColors(
    accent = Color(0xFFA4512A),
    gold = Color(0xFFB8893E),
    ink = Color(0xFF252776),
    text = Color(0xFF1C1C1E),
    secondary = Color(0xFF6E6E73),
    tertiary = Color(0xFFAEAEB2),
    background = Color(0xFFF5F2EE),
    card = Color(0xFFFFFFFF),
    fill = Color(0xFFEFEAE4),
    separator = Color(0xFFE3DED8),
    dark = false,
)

private val DarkBrand = BrandColors(
    accent = Color(0xFFE08A5B),
    gold = Color(0xFFD9B677),
    ink = Color(0xFFFFFFFF),
    text = Color(0xFFF2F2F7),
    secondary = Color(0xFF98989F),
    tertiary = Color(0xFF636366),
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    fill = Color(0xFF2C2C2E),
    separator = Color(0xFF38383A),
    dark = true,
)

val LocalBrand = staticCompositionLocalOf { LightBrand }

object Brand {
    val colors: BrandColors @Composable @ReadOnlyComposable get() = LocalBrand.current
}

// Short aliases used across the screens.
val Cinnamon: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.accent
val CinnamonDark: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.ink
val Gold: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.gold
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.secondary
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.text
val Separator: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.separator
val CardColor: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.card
val FillColor: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.fill

// ------------------------------------------------------------------ type

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter – a clean sans close to Apple's SF Pro. */
val Inter = FontFamily(
    variable(R.font.inter, 400), variable(R.font.inter, 500),
    variable(R.font.inter, 600), variable(R.font.inter, 700),
)

/** Playfair Display – the hotel-brand serif for large titles. */
val Playfair = FontFamily(variable(R.font.playfair, 600), variable(R.font.playfair, 700))

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = Inter)
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Playfair),
        displayMedium = base.displayMedium.copy(fontFamily = Playfair),
        displaySmall = base.displaySmall.copy(fontFamily = Playfair),
        headlineLarge = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontFamily = Playfair, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
        titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.inter().copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun VanTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val b = if (dark) DarkBrand else LightBrand
    val scheme = if (dark) darkColorScheme(
        primary = b.accent, onPrimary = Color.White,
        primaryContainer = b.accent.copy(alpha = 0.22f), onPrimaryContainer = b.accent,
        secondary = b.gold, onSecondary = Color.Black,
        tertiary = b.gold,
        background = b.background, onBackground = b.text,
        surface = b.card, onSurface = b.text,
        surfaceVariant = b.fill, onSurfaceVariant = b.secondary,
        surfaceContainer = b.card, surfaceContainerHigh = b.fill, surfaceContainerHighest = b.fill,
        surfaceContainerLow = b.card, surfaceContainerLowest = b.background,
        outline = b.tertiary, outlineVariant = b.separator,
        error = DangerRed,
    ) else lightColorScheme(
        primary = b.accent, onPrimary = Color.White,
        primaryContainer = b.accent.copy(alpha = 0.12f), onPrimaryContainer = b.accent,
        secondary = b.gold, onSecondary = Color.White,
        tertiary = b.gold,
        background = b.background, onBackground = b.text,
        surface = b.card, onSurface = b.text,
        surfaceVariant = b.fill, onSurfaceVariant = b.secondary,
        surfaceContainer = b.card, surfaceContainerHigh = b.card, surfaceContainerHighest = b.fill,
        surfaceContainerLow = b.card, surfaceContainerLowest = b.background,
        outline = b.tertiary, outlineVariant = b.separator,
        error = DangerRed,
    )
    CompositionLocalProvider(LocalBrand provides b) {
        MaterialTheme(colorScheme = scheme, typography = appTypography(), shapes = AppShapes, content = content)
    }
}
