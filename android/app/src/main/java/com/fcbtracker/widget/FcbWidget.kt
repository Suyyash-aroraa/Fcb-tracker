package com.fcbtracker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
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
import com.fcbtracker.core.FCB_ESPN_ID
import com.fcbtracker.core.Match
import com.fcbtracker.core.Snapshot
import com.fcbtracker.core.TeamRef

private val Bg = Color(0xFF0B1020)
private val Text1 = Color(0xFFF2F4F8)
private val Text2 = Color(0xFFB0B9CD)
private val Gold = Color(0xFFEDBB00)
private val Grana = Color(0xFFA50044)
private val Blau = Color(0xFF004D98)
private val Win = Color(0xFF5CC98A)
private val Draw = Color(0xFF9AA3B5)
private val Loss = Color(0xFFE5687A)

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 180.dp)

class FcbWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Data.repo(context)
        val s = repo.load()
        val focus = s?.live ?: s?.next
        val teams = listOfNotNull(focus?.home, focus?.away, s?.last?.opponent) + s?.form.orEmpty().map { it.opponent }
        val crests = teams.distinctBy { it.id }.associate { t ->
            t.id to repo.crest(t)?.let { f -> BitmapFactory.decodeFile(f.path) }
        }
        provideContent { Content(s, crests) }
    }
}

class FcbWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FcbWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Sync.start(context)
    }
}

private fun style(size: TextUnit, color: Color = Text1, bold: Boolean = false, align: TextAlign = TextAlign.Start) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align)

@Composable
private fun Content(s: Snapshot?, crests: Map<String, Bitmap?>) {
    val size = LocalSize.current
    val wide = size.width >= WIDE.width
    val tall = size.height >= TALL.height
    Column(
        GlanceModifier.fillMaxSize().background(Bg).cornerRadius(20.dp).clickable(actionStartActivity<MainActivity>()),
    ) {
        // Blaugrana stripe
        Row(GlanceModifier.fillMaxWidth().height(4.dp)) {
            Box(GlanceModifier.defaultWeight().height(4.dp).background(Blau)) {}
            Box(GlanceModifier.defaultWeight().height(4.dp).background(Grana)) {}
        }
        Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val m = s?.live ?: s?.next
            if (s == null || m == null) {
                Text(if (s == null) "FCB Tracker\nTap to load" else "No fixtures scheduled", style = style(13.sp, Text2))
                return@Column
            }
            Header(m, wide)
            Spacer(GlanceModifier.defaultWeight())
            Fixture(m, crests, wide)
            Spacer(GlanceModifier.defaultWeight())
            if (wide) Footer(s, m, crests, tall)
        }
    }
}

@Composable
private fun Header(m: Match, wide: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (m.isLive) {
            Text("LIVE ${Ui.clock(m)}", style = style(11.sp, Color(0xFFFF5C8A), bold = true))
        } else {
            Text(if (wide) "${Ui.compShort(m)}  ·  ${Ui.dayLabel(m)}" else Ui.dayLabel(m), style = style(11.sp, Text2), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        if (!m.isLive && wide) Text(Ui.countdown(m), style = style(11.sp, Gold, bold = true))
    }
}

@Composable
private fun Crest(t: TeamRef, crests: Map<String, Bitmap?>, size: Int) {
    val b = crests[t.id]
    if (b != null) Image(ImageProvider(b), contentDescription = t.name, modifier = GlanceModifier.size(size.dp))
    else Box(GlanceModifier.size(size.dp).background(Color(0xFF1C2540)).cornerRadius((size / 2).dp), contentAlignment = Alignment.Center) {
        Text(t.abbr ?: t.short.take(3), style = style(9.sp, Text2, bold = true))
    }
}

@Composable
private fun Fixture(m: Match, crests: Map<String, Bitmap?>, wide: Boolean) {
    val mid = if (m.isLive || m.isPlayed) m.scoreText ?: "-" else Ui.time(m)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (wide) {
            Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.End) {
                Text(m.home.short, style = style(14.sp, Text1, bold = m.home.id == FCB_ESPN_ID, align = TextAlign.End), maxLines = 1)
                Spacer(GlanceModifier.width(6.dp))
                Crest(m.home, crests, 30)
            }
            Text(mid, style = style(if (m.isUpcoming) 18.sp else 26.sp, if (m.isLive) Gold else Text1, bold = true, align = TextAlign.Center),
                modifier = GlanceModifier.width(92.dp), maxLines = 1)
            Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Crest(m.away, crests, 30)
                Spacer(GlanceModifier.width(6.dp))
                Text(m.away.short, style = style(14.sp, Text1, bold = m.away.id == FCB_ESPN_ID), maxLines = 1)
            }
        } else {
            Crest(m.home, crests, 28)
            // The header already shows the kick-off time on the small size.
            Text(if (m.isUpcoming) "v" else mid, style = style(if (m.isUpcoming) 14.sp else 20.sp, if (m.isLive) Gold else Text1, bold = true, align = TextAlign.Center),
                modifier = GlanceModifier.defaultWeight())
            Crest(m.away, crests, 28)
        }
    }
    if (!wide) {
        Spacer(GlanceModifier.height(4.dp))
        Text(if (m.isLive) m.opponent.short else "vs ${m.opponent.short}", style = style(11.sp, Text2, align = TextAlign.Center),
            modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
        m.tv?.channels?.firstOrNull()?.let {
            Text(it, style = style(10.sp, Gold, align = TextAlign.Center), modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
        }
    }
}

@Composable
private fun Footer(s: Snapshot, m: Match, crests: Map<String, Bitmap?>, tall: Boolean) {
    m.tv?.let { tv ->
        Text("India: ${tv.channels.joinToString(", ")}", style = style(11.sp, Gold), maxLines = 1)
    }
    if (tall) {
        s.last?.takeIf { it.id != m.id }?.let { last ->
            Spacer(GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last  ", style = style(11.sp, Text2))
                ResultDot(last.result)
                Spacer(GlanceModifier.width(6.dp))
                Text("${last.fcb.score}-${last.opponent.score} ${if (last.fcbSide == "home") "vs" else "at"} ${last.opponent.short}", style = style(12.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Form  ", style = style(11.sp, Text2))
            s.form.forEach { ResultDot(it.result); Spacer(GlanceModifier.width(4.dp)) }
            Spacer(GlanceModifier.defaultWeight())
            s.position?.let { Text("${it.rank}${Ui.ordinal(it.rank)} · ${it.points} pts", style = style(11.sp, Text2)) }
        }
    }
}

@Composable
private fun ResultDot(r: String?) {
    val c = when (r) { "W" -> Win; "L" -> Loss; else -> Draw }
    Box(GlanceModifier.size(18.dp).background(c).cornerRadius(9.dp), contentAlignment = Alignment.Center) {
        Text(r ?: "-", style = style(10.sp, Color(0xFF0B1020), bold = true, align = TextAlign.Center))
    }
}
