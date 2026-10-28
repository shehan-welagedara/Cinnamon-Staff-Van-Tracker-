package com.cinnamon.vantracker.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

// ------------------------------------------------------------------ building blocks

/**
 * iOS-style navigation bar. Screens without [onBack] (home tabs) get a large serif title;
 * pushed screens get a compact centred title with a "‹ Back" button in the brand tint.
 */
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val b = Brand.colors
    val haze = LocalHaze.current
    Column(
        Modifier
            .fillMaxWidth()
            // Glass bar: blurs the content scrolling underneath (GlassScaffold), else a light frosted tint.
            .then(if (haze != null) Modifier.hazeEffect(haze, glassStyle()) else Modifier.background(b.background.copy(alpha = 0.55f)))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp)) {
            if (onBack != null) {
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = b.accent, modifier = Modifier.size(30.dp))
                    Text("Back", color = b.accent, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = b.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                )
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides b.accent) { actions() }
            }
        }
        if (onBack == null) {
            // Brand hierarchy: spaced caps eyebrow → bold title.
            Text(
                "KANDY MYST BY CINNAMON", style = MaterialTheme.typography.bodySmall, color = b.secondary,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
            )
            Text(
                title, style = MaterialTheme.typography.headlineLarge, color = b.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(b.separator))
        }
    }
}

/** Brand glass card: 22dp radius, frosted translucent fill, bright top edge, soft purple shadow. */
fun Modifier.brandCard(b: BrandColors, radius: Dp = 22.dp): Modifier = this
    .padding(horizontal = 16.dp, vertical = 6.dp)
    .shadow(
        elevation = if (b.dark) 0.dp else 8.dp, shape = RoundedCornerShape(radius),
        ambientColor = b.shadow.copy(alpha = 0.10f), spotColor = b.shadow.copy(alpha = 0.14f),
    )
    .clip(RoundedCornerShape(radius))
    .background(if (b.dark) b.card.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.74f))
    .border(1.dp, glassEdge(b.dark), RoundedCornerShape(radius))

/** Inset grouped card (iOS Settings style). [accent] draws a slim status strip on the left. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val b = Brand.colors
    Box(
        modifier
            .fillMaxWidth()
            .brandCard(b)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (accent != null) Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth()) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = b.text)
                    Spacer(Modifier.height(8.dp))
                }
                content()
            }
        }
    }
}

/** Brand eyebrow (caps, spaced, muted violet) above a group of cards. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 4.dp),
    )
}

/** Brand page tab: purple gradient, flush left, one rounded corner. */
@Composable
fun PageTab(text: String) {
    Text(
        text.uppercase(), color = Color.White, style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(topEnd = 26.dp))
            .background(BrandGradient)
            .padding(start = 20.dp, end = 36.dp, top = 9.dp, bottom = 9.dp),
    )
}

/** Brand pill label: leaf corners, solid purple (or outlined). */
@Composable
fun PillLabel(text: String, outlined: Boolean = false) {
    val b = Brand.colors
    val shape = leafShape(18.dp, 3.dp)
    Text(
        text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
        color = if (outlined) b.accent else Color.White,
        modifier = Modifier
            .clip(shape)
            .then(if (outlined) Modifier.border(1.5.dp, b.accent, shape) else Modifier.background(b.accent))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Numbered purple circle (brand "numbered steps"). */
@Composable
fun StepNumber(n: Int) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(Cinnamon), contentAlignment = Alignment.Center) {
        Text(n.toString(), color = if (Brand.colors.dark) Color.Black else Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (valueColor == Color.Unspecified) TextPrimary else valueColor,
        )
    }
}

/** Tinted status label with the brand leaf corners. */
@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Figtree,
        modifier = Modifier
            .background(color.copy(alpha = if (Brand.colors.dark) 0.24f else 0.13f), leafShape(14.dp, 3.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Inset, tinted notice. */
@Composable
fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (Brand.colors.dark) 0.22f else 0.12f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary)
    }
}

/** Card holding a list of tappable rows separated by hairlines. */
@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .brandCard(Brand.colors),
        content = content,
    )
}

@Composable
fun ListRow(icon: String, text: String, last: Boolean = false, tint: Color = Color.Unspecified, value: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp, modifier = Modifier.width(30.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (tint == Color.Unspecified) TextPrimary else tint, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.colors.tertiary)
    }
    if (!last) Box(Modifier.padding(start = 46.dp).fillMaxWidth().height(0.5.dp).background(Separator))
}

/** iOS segmented control. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val b = Brand.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(b.fill)
            .padding(2.dp)
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) (if (b.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = MaterialTheme.typography.bodySmall, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = b.text)
            }
        }
    }
}

/** Appearance chooser: follow the phone, or force light / dark. */
@Composable
fun AppearancePicker() {
    val ctx = LocalContext.current
    Segmented(ThemeMode.entries.map { it.label }, ThemeMode.entries.indexOf(ThemePrefs.mode)) {
        ThemePrefs.set(ctx, ThemeMode.entries[it])
    }
}

/** Simple drop-down chooser that works the same on every Material3 version. */
@Composable
fun Picker(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent layer that captures taps (text fields swallow clicks otherwise).
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            c.set(y, m, d, h, min, 0)
            onPicked(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

fun pickTime(ctx: Context, hhmm: String, onPicked: (String) -> Unit) {
    val parts = hhmm.split(":")
    TimePickerDialog(ctx, { _, h, m ->
        onPicked("%02d:%02d".format(h, m))
    }, parts.getOrNull(0)?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
}

// ------------------------------------------------------------------ connectivity

@Composable
fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(isOnline(ctx)) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = isOnline(ctx) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

