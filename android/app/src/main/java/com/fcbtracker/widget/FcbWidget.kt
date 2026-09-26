package com.fcbtracker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.fcbtracker.R
import com.fcbtracker.app.Data
import com.fcbtracker.app.MainActivity
import com.fcbtracker.app.Sync
import com.fcbtracker.app.Ui
import com.fcbtracker.core.Match
import com.fcbtracker.core.MatchDetail
import com.fcbtracker.core.Snapshot
import com.fcbtracker.core.TeamRef
import java.time.Duration
import java.time.Instant

// The widget is the app's hero: always on the deep navy field with the blaugrana edge.
private val HeroBg = Color(0xFF0D1530)
private val Raised = Color(0x14FFFFFF)
private val Text1 = Color(0xFFEEF1F7)
private val Text2 = Color(0xFFB8C1D6)
private val Text3 = Color(0xFF8F9AB3)
private val AccentText = Color(0xFFF2C94C)
private val Blau = Color(0xFF1C4FA3)
private val Grana = Color(0xFFA3113F)
private val Win = Color(0xFF3EC48A)
private val Draw = Color(0xFFA3ACBF)
private val Loss = Color(0xFFF06A6F)

private val SMALL = DpSize(120.dp, 120.dp)
private val WIDE = DpSize(250.dp, 120.dp)
private val TALL = DpSize(250.dp, 230.dp)

/** What the widget shows, in order: a live match, a result from the last 8 hours, else the next fixture. */
private data class Focus(val match: Match, val state: String, val detail: MatchDetail?)

private fun focus(s: Snapshot?, detail: (String) -> MatchDetail?, now: Instant = Instant.now()): Focus? {
    s ?: return null
    s.live?.let { return Focus(it, "live", detail(it.id)) }
    s.last?.takeIf { Duration.between(it.kickoff, now) < Duration.ofHours(8) }?.let { return Focus(it, "ft", detail(it.id)) }
    return s.next?.let { Focus(it, "next", null) }
}

class FcbWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) = render(context)

    /** The widget picker's preview (Android 15+) shows the real next match. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) = render(context)

    private suspend fun render(context: Context) {
        val repo = Data.repo(context)
        val s = repo.load()
        val f = focus(s, repo::cachedDetail)
        val teams = listOfNotNull(f?.match?.home, f?.match?.away) + s?.form.orEmpty().map { it.opponent } + listOfNotNull(s?.upcoming?.getOrNull(1)?.opponent)
        val crests = teams.distinctBy { it.id }.associate { t -> t.id to repo.crest(t)?.let { BitmapFactory.decodeFile(it.path) } }
        val art = Art(context)
        provideContent { Content(s, f, crests, art) }
    }
}

class FcbWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FcbWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Sync.start(context)
    }
}

/** Renders display type (Big Shoulders) to bitmaps, since widget text can only use system fonts. */
private class Art(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val display = runCatching { ResourcesCompat.getFont(context, R.font.big_shoulders_extrabold) }.getOrNull() ?: Typeface.DEFAULT_BOLD

    data class Rendered(val bitmap: Bitmap, val width: Dp, val height: Dp)

    fun text(text: String, size: Float, color: Color, letterSpacing: Float = 0.02f): Rendered {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = display; textSize = size * density; this.color = color.toArgb(); this.letterSpacing = letterSpacing
        }
        val fm = paint.fontMetrics
        val w = maxOf(1, kotlin.math.ceil(paint.measureText(text).toDouble()).toInt())
        val h = maxOf(1, kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 0f, -fm.ascent, paint)
        return Rendered(bmp, (w / density).dp, (h / density).dp)
    }

    /** The left-edge stripe: alternating blau and grana bands, like the web hero. */
    fun edge(heightDp: Int): Bitmap {
        val w = (6 * density).toInt().coerceAtLeast(1); val h = (heightDp * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(); val band = 22 * density
        var y = 0f; var i = 0
        while (y < h) { p.color = (if (i % 2 == 0) Blau else Grana).toArgb(); c.drawRect(0f, y, w.toFloat(), y + band, p); y += band; i++ }
        return bmp
    }
}

private fun style(size: TextUnit, color: Color = Text1, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, textAlign = align)

@Composable
private fun Display(art: Art, text: String, size: Float, color: Color = Text1, description: String? = null) {
    val r = art.text(text, size, color)
    Image(ImageProvider(r.bitmap), contentDescription = description ?: text, contentScale = ContentScale.Fit,
        modifier = GlanceModifier.width(r.width).height(r.height))
}

@Composable
private fun Content(s: Snapshot?, f: Focus?, crests: Map<String, Bitmap?>, art: Art) {
    val size = LocalSize.current
    val wide = size.width >= WIDE.width
    val tall = size.height >= TALL.height && wide
    Row(
        GlanceModifier.fillMaxSize().background(HeroBg).cornerRadius(22.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        Image(ImageProvider(art.edge(400)), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = GlanceModifier.width(6.dp).fillMaxHeight())
        Column(GlanceModifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            if (s == null || f == null) {
                Display(art, "FCB TRACKER", 20f)
                Spacer(GlanceModifier.height(4.dp))
                Text(if (s == null) "Tap to load Barça's matches" else "No fixtures scheduled", style = style(12.sp, Text2))
                return@Column
            }
            Kicker(f, wide)
            Spacer(GlanceModifier.defaultWeight())
            if (wide) WideFixture(f, crests, art, compact = tall) else SmallFixture(f, crests, art)
            Spacer(GlanceModifier.defaultWeight())
            // Glance renders at most 10 children per container, so the footer is its own column.
            Column(GlanceModifier.fillMaxWidth()) { if (wide) Footer(s, f, tall, crests) else SmallFooter(f) }
        }
    }
}

@Composable
private fun Kicker(f: Focus, wide: Boolean) {
    val m = f.match
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (f.state) {
            "live" -> Box(GlanceModifier.background(Loss).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("LIVE  ${Ui.clock(m)}", style = style(11.sp, Color.White, bold = true))
            }
            "ft" -> Text("FULL TIME", style = style(11.sp, AccentText, bold = true))
            else -> Text(Ui.compShort(m).uppercase(), style = style(11.sp, AccentText, bold = true), maxLines = 1)
        }
        Text(
            "  ·  " + when (f.state) { "next" -> if (wide) Ui.dayLabel(m) else Ui.dayLabel(m).substringBefore(","); else -> Ui.compShort(m) },
            style = style(11.sp, Text2), maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        if (f.state == "next" && wide) {
            Box(GlanceModifier.background(Raised).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(Ui.countdown(m).removePrefix("in ").uppercase(), style = style(11.sp, AccentText, bold = true))
            }
        }
    }
}

@Composable
private fun Crest(t: TeamRef, crests: Map<String, Bitmap?>, size: Int) {
    val b = crests[t.id]
    if (b != null) Image(ImageProvider(b), contentDescription = t.name, modifier = GlanceModifier.size(size.dp))
    else Box(GlanceModifier.size(size.dp).background(Raised).cornerRadius((size / 2).dp), contentAlignment = Alignment.Center) {
        Text(t.abbr ?: t.short.take(3), style = style(10.sp, Text2, bold = true))
    }
}

private fun centre(m: Match) = if (m.isUpcoming) Ui.time(m) else "${m.home.score ?: 0}-${m.away.score ?: 0}"

@Composable
private fun WideFixture(f: Focus, crests: Map<String, Bitmap?>, art: Art, compact: Boolean) {
    val crest = if (compact) 34 else 40
    val m = f.match
    val label = if (m.isUpcoming) "${m.home.name} versus ${m.away.name}, ${Ui.dayLabel(m)}" else "${m.home.name} ${m.home.score}, ${m.away.name} ${m.away.score}"
    Row(GlanceModifier.fillMaxWidth().semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Crest(m.home, crests, crest)
            Spacer(GlanceModifier.height(3.dp))
            Display(art, m.home.short.uppercase().take(12), if (compact) 13f else 15f)
        }
        Box(GlanceModifier.width(104.dp), contentAlignment = Alignment.Center) {
            Display(art, centre(m), if (m.isUpcoming) (if (compact) 26f else 30f) else (if (compact) 34f else 40f))
        }
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Crest(m.away, crests, crest)
            Spacer(GlanceModifier.height(3.dp))
            Display(art, m.away.short.uppercase().take(12), if (compact) 13f else 15f)
        }
    }
}

@Composable
private fun SmallFixture(f: Focus, crests: Map<String, Bitmap?>, art: Art) {
    val m = f.match
    Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crest(m.home, crests, 34)
            Spacer(GlanceModifier.width(10.dp))
            Crest(m.away, crests, 34)
        }
        Spacer(GlanceModifier.height(4.dp))
        Display(art, centre(m), if (m.isUpcoming) 24f else 30f)
    }
}

@Composable
private fun SmallFooter(f: Focus) {
    val m = f.match
    val tv = m.tv?.channels?.firstOrNull()?.takeIf { f.state == "next" }
    Text(
        if (tv != null) "vs ${m.opponent.short} · $tv" else "vs ${m.opponent.short}",
        style = style(11.sp, if (tv != null) AccentText else Text2, align = TextAlign.Center),
        modifier = GlanceModifier.fillMaxWidth(), maxLines = 1,
    )
}

@Composable
private fun Footer(s: Snapshot, f: Focus, tall: Boolean, crests: Map<String, Bitmap?>) {
    val m = f.match
    // Live / full time: Barça's scorers. Next: where to watch in India, and form.
    if (f.state != "next") {
        val goals = f.detail?.events.orEmpty().filter { (it.kind == "goal" || it.kind == "own-goal") && it.side == m.fcbSide }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_soccer_ball_fill), contentDescription = null, colorFilter = ColorFilter.tint(ColorProvider(AccentText)),
                modifier = GlanceModifier.size(13.dp))
            Spacer(GlanceModifier.width(6.dp))
            Text(
                if (goals.isEmpty()) (if (f.state == "live") "No Barça goals yet" else "Barça didn't score")
                else goals.groupBy { it.players.firstOrNull() ?: "OG" }.entries.joinToString("   ") { (who, gs) -> "${who.substringAfterLast(' ')} ${gs.joinToString(", ") { it.minute }}" },
                style = style(12.sp, Text1), maxLines = 1,
            )
        }
    } else {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_television_simple), contentDescription = null, colorFilter = ColorFilter.tint(ColorProvider(Text3)),
                modifier = GlanceModifier.size(14.dp))
            Spacer(GlanceModifier.width(6.dp))
            Text(m.tv?.channels?.joinToString(", ") ?: "India TV not listed yet", style = style(12.sp, if (m.tv != null) AccentText else Text3, bold = m.tv != null), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            if (!tall) FormChips(s, small = true)
        }
    }
    if (tall) Column(GlanceModifier.fillMaxWidth()) {
        Spacer(GlanceModifier.height(7.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF))) {}
        Spacer(GlanceModifier.height(7.dp))
        s.last?.takeIf { it.id != m.id }?.let { last ->
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LAST", style = style(10.sp, Text3, bold = true), modifier = GlanceModifier.width(44.dp))
                Chip(last.result)
                Spacer(GlanceModifier.width(8.dp))
                Text("${last.fcb.score}-${last.opponent.score} ${if (last.fcbSide == "home") "vs" else "at"} ${last.opponent.short}",
                    style = style(12.sp, Text1), maxLines = 1)
            }
            Spacer(GlanceModifier.height(5.dp))
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FORM", style = style(10.sp, Text3, bold = true), modifier = GlanceModifier.width(44.dp))
            FormChips(s, small = false)
            Spacer(GlanceModifier.defaultWeight())
            s.position?.let { Text("${it.rank}${Ui.ordinal(it.rank)}  ·  ${it.points} pts", style = style(12.sp, Text2, bold = true)) }
        }
        s.upcoming.firstOrNull { it.id != m.id }?.let { after ->
            Spacer(GlanceModifier.height(5.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("THEN", style = style(10.sp, Text3, bold = true), modifier = GlanceModifier.width(44.dp))
                Crest(after.opponent, crests, 16)
                Spacer(GlanceModifier.width(6.dp))
                Text("${if (after.fcbSide == "home") "vs" else "at"} ${after.opponent.short}  ·  ${Ui.dayLabel(after)}", style = style(12.sp, Text2), maxLines = 1)
            }
        }
    }
}

@Composable
private fun FormChips(s: Snapshot, small: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.form.forEachIndexed { i, m -> if (i > 0) Spacer(GlanceModifier.width(3.dp)); Chip(m.result, if (small) 16 else 18) }
    }
}

@Composable
private fun Chip(r: String?, size: Int = 18) {
    val c = when (r) { "W" -> Win; "L" -> Loss; "D" -> Draw; else -> Raised }
    Box(GlanceModifier.size(size.dp).background(c).cornerRadius(5.dp), contentAlignment = Alignment.Center) {
        Text(r ?: "-", style = style((size * 0.55f).sp, HeroBg, bold = true, align = TextAlign.Center))
    }
}
