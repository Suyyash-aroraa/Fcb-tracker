package com.fcbtracker.app

import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.R
import com.fcbtracker.core.TeamRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------- tokens (same values as the web app)

/** Semantic colour tokens. Components only ever reference these. */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val surface3: Color, val line: Color,
    val text: Color, val text2: Color, val text3: Color,
    val accent: Color, val onAccent: Color, val accentText: Color,
    val win: Color, val draw: Color, val loss: Color, val onStatus: Color,
    val heroBg: Color, val pitch: Color, val pitchLine: Color, val dark: Boolean,
)

val Blau = Color(0xFF1C4FA3)
val Grana = Color(0xFFA3113F)
val Gold = Color(0xFFEDBB00)

private val DarkPalette = Palette(
    bg = Color(0xFF0A0E1A), surface = Color(0xFF111729), surface2 = Color(0xFF182038), surface3 = Color(0xFF212B47), line = Color(0xFF27314D),
    text = Color(0xFFEEF1F7), text2 = Color(0xFFB0B9CD), text3 = Color(0xFF8591AB),
    accent = Gold, onAccent = Color(0xFF1D1600), accentText = Color(0xFFF2C94C),
    win = Color(0xFF3EC48A), draw = Color(0xFFA3ACBF), loss = Color(0xFFF06A6F), onStatus = Color(0xFF0A0E1A),
    heroBg = Color(0xFF0D1530), pitch = Color(0xFF0F2A22), pitchLine = Color(0x24FFFFFF), dark = true,
)
private val LightPalette = Palette(
    bg = Color(0xFFEEF1F6), surface = Color(0xFFFBFCFE), surface2 = Color(0xFFE6EAF2), surface3 = Color(0xFFD9DFEB), line = Color(0xFFD3D9E6),
    text = Color(0xFF0D1324), text2 = Color(0xFF3D4963), text3 = Color(0xFF56627C),
    accent = Gold, onAccent = Color(0xFF1D1600), accentText = Color(0xFF7A5C00),
    win = Color(0xFF147A4D), draw = Color(0xFF5A6478), loss = Color(0xFFC02F36), onStatus = Color(0xFFFBFCFE),
    heroBg = Color(0xFF0F1A3D), pitch = Color(0xFF1D5A44), pitchLine = Color(0x47FFFFFF), dark = false,
)

/** The hero is always on its deep navy field, in both themes (like the web app's .hero). */
val HeroPalette = DarkPalette.copy(text = Color(0xFFEEF1F7), text2 = Color(0xFFB8C1D6), text3 = Color(0xFF8F9AB3),
    line = Color(0x1AFFFFFF), surface2 = Color(0x0FFFFFFF), bg = Color(0xFF0D1530), surface = Color(0xFF0D1530))

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable get() = LocalPalette.current

/** Radius scale: panels 14, controls pill, markers circle. */
val PanelShape = RoundedCornerShape(14.dp)
val PillShape = RoundedCornerShape(50)

// ---------------------------------------------------------------- type

val Display = FontFamily(Font(R.font.big_shoulders_bold, FontWeight.Bold), Font(R.font.big_shoulders_extrabold, FontWeight.ExtraBold))
val UiFont = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal), Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold), Font(R.font.geist_bold, FontWeight.Bold),
)
/** Geist Mono: every number that aligns or updates. */
val Num = FontFamily(Font(R.font.geist_mono_medium, FontWeight.Medium), Font(R.font.geist_mono_semibold, FontWeight.SemiBold))

object T {
    val display = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold)
    val body = TextStyle(fontFamily = UiFont, fontSize = 15.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = UiFont, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = UiFont, fontSize = 12.sp)
    val num = TextStyle(fontFamily = Num, fontWeight = FontWeight.Medium)
}

@Composable
fun FcbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.surface, onSurface = p.text,
        onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2, outline = p.line, outlineVariant = p.line,
        secondaryContainer = p.accent, onSecondaryContainer = p.onAccent, surfaceContainer = p.surface,
        surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerLowest = p.bg,
    )
    val base = Typography()
    val type = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = UiFont), bodyMedium = base.bodyMedium.copy(fontFamily = UiFont),
        bodySmall = base.bodySmall.copy(fontFamily = UiFont), labelLarge = base.labelLarge.copy(fontFamily = UiFont, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = UiFont), labelSmall = base.labelSmall.copy(fontFamily = UiFont),
        titleMedium = base.titleMedium.copy(fontFamily = UiFont), titleSmall = base.titleSmall.copy(fontFamily = UiFont),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

// ---------------------------------------------------------------- motion

/** True when the system's animator scale is off (the Android form of prefers-reduced-motion). */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Press feedback: a slight scale while pressed, plus the ripple. */
fun Modifier.pressable(onClick: () -> Unit, label: String? = null): Modifier = this.then(
    Modifier.composedPress(onClick, label),
)

@Composable
private fun Modifier.composedPressImpl(onClick: () -> Unit, label: String?): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reducedMotion()) 0.98f else 1f, tween(150), label = "press")
    return this.scale(scale).clickable(interactionSource = source, indication = ripple(), onClickLabel = label, onClick = onClick)
}

private fun Modifier.composedPress(onClick: () -> Unit, label: String?): Modifier =
    composed { composedPressImpl(onClick, label) }

// ---------------------------------------------------------------- building blocks

@Composable
fun Icon(@DrawableRes id: Int, size: Dp = 18.dp, tint: Color = P.text3, description: String? = null, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = description, tint = tint, modifier = modifier.size(size))

/** A panel: 14dp radius, a hairline and a surface tone. Used where elevation means hierarchy. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(PanelShape).background(P.surface).border(1.dp, P.line, PanelShape)) { content() }
}

/** Section heading, in the display face: at most one per region, no eyebrows. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = T.display.copy(fontSize = 20.sp, letterSpacing = 0.6.sp), color = P.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** W/D/L chip: always carries the letter, never colour alone. */
@Composable
fun WdlChip(result: String?, size: Dp = 24.dp) {
    val c = when (result) { "W" -> P.win; "L" -> P.loss; "D" -> P.draw; else -> P.surface3 }
    val label = when (result) { "W" -> "Won"; "L" -> "Lost"; "D" -> "Drew"; else -> "No result" }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(c).semanticsLabel(label), contentAlignment = Alignment.Center) {
        Text(result ?: "-", color = P.onStatus, style = T.display.copy(fontSize = (size.value * 0.58f).sp))
    }
}

fun Modifier.semanticsLabel(label: String) =
    this.semantics(mergeDescendants = true) { contentDescription = label }

/** The live flag: a pulsing dot (static with reduced motion) and LIVE. */
@Composable
fun LiveFlag() {
    val reduce = reducedMotion()
    val alpha = if (reduce) 1f else rememberInfiniteTransition(label = "live").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    ).value
    Row(Modifier.clip(PillShape).background(P.loss).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", color = Color.White, style = T.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp))
    }
}

/** Blaugrana stripe on the hero's left edge, as on the web app. */
fun Modifier.blaugranaEdge(width: Dp = 8.dp): Modifier = drawBehind {
    val w = width.toPx(); val band = 28.dp.toPx()
    var y = 0f; var i = 0
    while (y < size.height) {
        drawRect(if (i % 2 == 0) Blau else Grana, Offset(0f, y), Size(w, minOf(band, size.height - y)))
        y += band; i++
    }
}

/** Skeleton block shaped like the content it stands in for; shimmers unless motion is reduced. */
@Composable
fun Skeleton(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp)) {
    val reduce = reducedMotion()
    val a = if (reduce) 0.7f else rememberInfiniteTransition(label = "skel").animateFloat(
        0.45f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skel-a",
    ).value
    Box(modifier.clip(shape).background(P.surface2.copy(alpha = a)))
}

// ---------------------------------------------------------------- images, cached on the phone

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
private fun rememberBitmap(key: String, maxPx: Int, load: suspend () -> java.io.File?): ImageBitmap? {
    val bitmap by produceState(bitmapCache[key], key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            load()?.let { f ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }?.also { bitmapCache[key] = it }
        }
    }
    return bitmap
}

/** A team crest, downloaded once and kept on the phone. Falls back to the abbreviation. */
@Composable
fun Crest(team: TeamRef, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val b = rememberBitmap("crest:${team.id}", 192) { Data.repo(context).crest(team) }
    if (b != null) Image(b, contentDescription = null, modifier = modifier.size(size))
    else Box(modifier.size(size).clip(CircleShape).background(P.surface2), contentAlignment = Alignment.Center) {
        Text(team.abbr ?: team.short.take(3), color = P.text2, style = T.label.copy(fontSize = (size.value * 0.28f).sp, fontWeight = FontWeight.Bold))
    }
}

/** A remote photo (player portrait, news image) with a tone placeholder of the same size. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier, maxPx: Int = 480, crop: Alignment = Alignment.Center, fallback: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val b = if (url == null) null else rememberBitmap("img:$url", maxPx) { Data.repo(context).image(url) }
    Box(modifier.background(P.surface2), contentAlignment = Alignment.Center) {
        if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, alignment = crop, modifier = Modifier.matchParentSize())
        else fallback()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(P.line))

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape).background(if (selected) P.accent else P.surface).border(1.dp, if (selected) P.accent else P.line, PillShape)
            .pressable(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) P.onAccent else P.text2, style = T.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
