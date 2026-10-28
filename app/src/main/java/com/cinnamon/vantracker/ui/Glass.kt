package com.cinnamon.vantracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/*
 * "Liquid glass" (iOS-style): surfaces are translucent, frosted panes floating over a soft
 * brand-coloured backdrop. Bars and the tab bar really blur whatever scrolls beneath them
 * (Haze; Android 12+ – older phones get a translucent tint instead).
 */

/** Blur source of the current screen, if it uses [GlassScaffold]. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun glassStyle(): HazeStyle {
    val b = Brand.colors
    return if (b.dark) HazeStyle(
        backgroundColor = b.background,
        tint = HazeTint(Color(0xFF1B1524).copy(alpha = 0.58f)),
        blurRadius = 26.dp,
        noiseFactor = 0.06f,
    ) else HazeStyle(
        backgroundColor = b.background,
        tint = HazeTint(Color.White.copy(alpha = 0.58f)),
        blurRadius = 26.dp,
        noiseFactor = 0.05f,
    )
}

/** Specular edge of a glass pane: bright at the top, fading towards the bottom. */
fun glassEdge(dark: Boolean): Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = if (dark) 0.24f else 0.95f), Color.White.copy(alpha = if (dark) 0.04f else 0.30f))
)

/** Frosted glass surface. Real blur when the screen provides a haze source, else a translucent tint. */
@Composable
fun Modifier.glass(shape: Shape, haze: HazeState? = LocalHaze.current): Modifier {
    val b = Brand.colors
    val clipped = this.clip(shape)
    val filled = if (haze != null) clipped.hazeEffect(haze, glassStyle())
    else clipped.background(if (b.dark) b.card.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f))
    return filled.border(1.dp, glassEdge(b.dark), shape)
}

/** Soft lavender / purple / magenta light field the glass panes float over. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val b = Brand.colors
    Canvas(modifier) {
        drawRect(b.background)
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            val center = Offset(size.width * cx, size.height * cy)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = size.minDimension * r), size.minDimension * r, center)
        }
        if (b.dark) {
            blob(Color(0xFF592C82).copy(alpha = 0.55f), 0.05f, 0.08f, 1.0f)
            blob(Color(0xFF9A2580).copy(alpha = 0.28f), 1.0f, 0.45f, 0.85f)
            blob(Color(0xFF4A2271).copy(alpha = 0.45f), 0.2f, 1.0f, 0.9f)
        } else {
            blob(Color(0xFFB993E6).copy(alpha = 0.55f), 0.0f, 0.05f, 1.0f)
            blob(Color(0xFFE7A6D8).copy(alpha = 0.45f), 1.0f, 0.42f, 0.85f)
            blob(Color(0xFFCDB7E0).copy(alpha = 0.70f), 0.25f, 1.0f, 0.95f)
        }
    }
}

/**
 * Screen frame where content scrolls *under* a glass top bar and a floating glass tab bar.
 * Pass the given padding to the scrolling list as contentPadding (not as Modifier.padding).
 */
@Composable
fun GlassScaffold(
    topBar: @Composable () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val haze = remember { HazeState() }
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topDp = with(density) { topPx.toDp() }
    val bottomDp = if (bottomBar != null) with(density) { bottomPx.toDp() } else navInset
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            GlassBackdrop(Modifier.fillMaxSize())
            content(PaddingValues(top = topDp, bottom = bottomDp + 16.dp))
        }
        CompositionLocalProvider(LocalHaze provides haze) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }) { topBar() }
            if (bottomBar != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }) { bottomBar() }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = bottomDp + 12.dp)) { floatingActionButton() }
        }
    }
}

data class GlassTab(val label: String, val icon: ImageVector, val badge: Int = 0)

/** Floating capsule tab bar made of glass, with a tinted bubble behind the selected tab. */
@Composable
fun GlassTabBar(tabs: List<GlassTab>, selected: Int, onSelect: (Int) -> Unit) {
    val b = Brand.colors
    val shape = RoundedCornerShape(34.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(18.dp, shape, ambientColor = b.shadow.copy(alpha = 0.25f), spotColor = b.shadow.copy(alpha = 0.25f))
            .glass(shape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (on) b.accent.copy(alpha = if (b.dark) 0.28f else 0.14f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (on) b.accent else b.secondary
                if (t.badge > 0) BadgedBox(badge = { Badge { Text(t.badge.toString()) } }) { Icon(t.icon, t.label, tint = tint) }
                else Icon(t.icon, t.label, tint = tint)
                Text(t.label, fontSize = 10.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}
