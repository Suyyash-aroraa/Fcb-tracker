package com.fcbtracker.app

import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.core.TeamRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Design tokens, matching the web app: navy surfaces, blaugrana as a stripe, gold as the one accent. */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val text2: Color, val gold: Color, val onGold: Color,
    val win: Color, val draw: Color, val loss: Color, val live: Color,
)

val Blau = Color(0xFF004D98)
val Grana = Color(0xFFA50044)

private val DarkPalette = Palette(
    bg = Color(0xFF0B1020), surface = Color(0xFF121A2E), surface2 = Color(0xFF1C2540), line = Color(0xFF27314D),
    text = Color(0xFFF2F4F8), text2 = Color(0xFFB0B9CD), gold = Color(0xFFEDBB00), onGold = Color(0xFF1A1400),
    win = Color(0xFF5CC98A), draw = Color(0xFF9AA3B5), loss = Color(0xFFE5687A), live = Color(0xFFFF5C8A),
)
private val LightPalette = Palette(
    bg = Color(0xFFF4F6FB), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEDF0F7), line = Color(0xFFD3D9E6),
    text = Color(0xFF0B1020), text2 = Color(0xFF3D4963), gold = Color(0xFF8A6A00), onGold = Color(0xFFFFFFFF),
    win = Color(0xFF1E8A4C), draw = Color(0xFF5E6778), loss = Color(0xFFC0304A), live = Color(0xFFC0104A),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable get() = LocalPalette.current

/** Condensed display face for scores and headings (the system's condensed sans). */
val Display = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

@Composable
fun FcbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.gold, onPrimary = p.onGold, background = p.bg, surface = p.surface, onSurface = p.text,
        onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2, outline = p.line,
        secondaryContainer = p.gold, onSecondaryContainer = p.onGold,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private val crestCache = ConcurrentHashMap<String, ImageBitmap>()

/** A team crest, downloaded once and kept on the phone. Falls back to the abbreviation. */
@Composable
fun Crest(team: TeamRef, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(crestCache[team.id], team.id) {
        if (value == null) value = withContext(Dispatchers.IO) {
            Data.repo(context).crest(team)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()?.also { crestCache[team.id] = it }
        }
    }
    val b = bitmap
    if (b != null) Image(b, contentDescription = null, modifier = modifier.size(size))
    else Box(modifier.size(size).clip(CircleShape).background(P.surface2), contentAlignment = Alignment.Center) {
        Text(team.abbr ?: team.short.take(3), color = P.text2, fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ResultBadge(result: String?, size: Dp = 24.dp) {
    val c = when (result) { "W" -> P.win; "L" -> P.loss; else -> P.draw }
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(c), contentAlignment = Alignment.Center) {
        Text(result ?: "-", color = Color(0xFF0B1020), fontFamily = Display, fontSize = (size.value * 0.55f).sp)
    }
}
