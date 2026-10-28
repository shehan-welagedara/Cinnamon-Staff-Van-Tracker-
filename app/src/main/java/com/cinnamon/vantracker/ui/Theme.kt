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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.R

/*
 * Design language: Apple-style layout (grouped inset cards, large titles, hairline
 * separators, tinted controls) dressed in the Kandy Myst by Cinnamon brand kit v1.0:
 * Cinnamon Purple / Deep Purple / Magenta accent on Lavender, Figtree type,
 * leaf-corner pills and soft purple card shadows.
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

/** Functional status colours (trip states, alerts) – kept apart from the brand accents. */
val OkGreen = Color(0xFF2E9E5B)
val WarnOrange = Color(0xFFE5850B)
val DangerRed = Color(0xFFE0342B)
val InfoBlue = Color(0xFF2F6FDB)

@Immutable
data class BrandColors(
    val accent: Color,        // Cinnamon Purple – primary actions, tabs, headings
    val deep: Color,          // Deep Purple – gradient end
    val magenta: Color,       // Magenta Accent – used sparingly
    val ink: Color,           // headings
    val text: Color,          // body text (Ink)
    val secondary: Color,     // Muted Violet – captions
    val tertiary: Color,
    val background: Color,    // Lavender page background
    val card: Color,          // White cards
    val fill: Color,          // input / tinted fills
    val separator: Color,     // Rule Lilac – dividers, outlines
    val shadow: Color,
    val dark: Boolean,
)

/** Values from the Kandy Myst brand kit (page 04). */
private val LightBrand = BrandColors(
    accent = Color(0xFF592C82),
    deep = Color(0xFF4A2271),
    magenta = Color(0xFF9A2580),
    ink = Color(0xFF592C82),
    text = Color(0xFF0B0B0B),
    secondary = Color(0xFF6F5590),
    tertiary = Color(0xFFB7A6CC),
    background = Color(0xFFF5EBF6),
    card = Color(0xFFFFFFFF),
    fill = Color(0xFFF0E4F2),
    separator = Color(0xFFCDB7E0),
    shadow = Color(0xFF592C82),
    dark = false,
)

/** Dark mode derived from the same hues, lifted for contrast on near-black. */
private val DarkBrand = BrandColors(
    accent = Color(0xFFB993E6),
    deep = Color(0xFF4A2271),
    magenta = Color(0xFFDB6BC2),
    ink = Color(0xFFFFFFFF),
    text = Color(0xFFF4EEF7),
    secondary = Color(0xFFAE9CC6),
    tertiary = Color(0xFF6B5C80),
    background = Color(0xFF0D0A12),
    card = Color(0xFF1B1524),
    fill = Color(0xFF2A2236),
    separator = Color(0xFF3A3048),
    shadow = Color(0xFF000000),
    dark = true,
)

val LocalBrand = staticCompositionLocalOf { LightBrand }

object Brand {
    val colors: BrandColors @Composable @ReadOnlyComposable get() = LocalBrand.current
}

// Short aliases used across the screens.
val Cinnamon: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.accent
val CinnamonDark: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.ink
val Magenta: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.magenta
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.secondary
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.text
val Separator: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.separator
val CardColor: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.card
val FillColor: Color @Composable @ReadOnlyComposable get() = LocalBrand.current.fill

/** Brand "leaf corner" (radius 30 / 4 / 30 / 4) used for pills and labels. */
fun leafShape(big: Dp = 30.dp, small: Dp = 4.dp) =
    RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = small, bottomStart = big)

/** Purple gradient used for page tabs, covers and hero headers. */
val BrandGradient: Brush = Brush.linearGradient(listOf(Color(0xFF592C82), Color(0xFF4A2271)))

// ------------------------------------------------------------------ type

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Figtree 400–900 – the brand kit's digital substitute for Proxima Nova. */
val Figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700), figtree(800), figtree(900))

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.f() = copy(fontFamily = Figtree)
    // Headlines: Bold / Black with tight tracking, per the brand type system.
    return Typography(
        displayLarge = base.displayLarge.f().copy(fontWeight = FontWeight.Black),
        displayMedium = base.displayMedium.f().copy(fontWeight = FontWeight.Black),
        displaySmall = base.displaySmall.f().copy(fontWeight = FontWeight.Black),
        headlineLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
        headlineMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
        headlineSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
        titleMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.f().copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        labelMedium = base.labelMedium.f().copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.f().copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun VanTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val b = if (dark) DarkBrand else LightBrand
    val scheme = if (dark) darkColorScheme(
        primary = b.accent, onPrimary = Color(0xFF1B0D2B),
        primaryContainer = b.accent.copy(alpha = 0.22f), onPrimaryContainer = b.accent,
        secondary = b.magenta, onSecondary = Color.Black,
        tertiary = b.magenta,
        // Transparent so the glass backdrop shows through every screen.
        background = Color.Transparent, onBackground = b.text,
        surface = b.card, onSurface = b.text,
        surfaceVariant = b.fill, onSurfaceVariant = b.secondary,
        surfaceContainer = b.card, surfaceContainerHigh = b.fill, surfaceContainerHighest = b.fill,
        surfaceContainerLow = b.card, surfaceContainerLowest = b.background,
        outline = b.tertiary, outlineVariant = b.separator,
        error = DangerRed,
    ) else lightColorScheme(
        primary = b.accent, onPrimary = Color.White,
        primaryContainer = b.accent.copy(alpha = 0.10f), onPrimaryContainer = b.accent,
        secondary = b.magenta, onSecondary = Color.White,
        tertiary = b.magenta,
        // Transparent so the glass backdrop shows through every screen.
        background = Color.Transparent, onBackground = b.text,
        surface = b.card, onSurface = b.text,
        surfaceVariant = b.fill, onSurfaceVariant = b.secondary,
        surfaceContainer = b.card, surfaceContainerHigh = b.card, surfaceContainerHighest = b.fill,
        surfaceContainerLow = b.card, surfaceContainerLowest = b.background,
        outline = b.separator, outlineVariant = b.separator,
        error = DangerRed,
    )
    CompositionLocalProvider(LocalBrand provides b) {
        MaterialTheme(colorScheme = scheme, typography = appTypography(), shapes = AppShapes, content = content)
    }
}
