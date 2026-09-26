package com.fcbtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.R
import com.fcbtracker.core.FCB_ESPN_ID
import com.fcbtracker.core.Match
import com.fcbtracker.core.MatchDetail
import com.fcbtracker.core.NewsItem
import com.fcbtracker.core.Player
import com.fcbtracker.core.Snapshot
import com.fcbtracker.core.TeamRef
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun OverviewScreen(state: UiState, onRefresh: () -> Unit, onOpen: (Match) -> Unit, onTab: (Tab) -> Unit) {
    val s = state.snapshot
    Screen(state.refreshing, onRefresh) {
        if (s == null || s.matches.isEmpty()) {
            item { OverviewSkeleton(state) }
            return@Screen
        }
        (s.live ?: s.next)?.let { m -> item(key = "hero") { Hero(m, state.liveDetail?.takeIf { it.match.id == m.id }, onOpen) } }
        s.last?.takeIf { s.live == null }?.let { last ->
            item(key = "last") { Block { LastResult(last, state.lastDetail?.takeIf { it.match.id == last.id }, onOpen) } }
        }
        if (s.form.isNotEmpty()) item(key = "form") { Block { FormStrip(s.form, onOpen) } }
        if (s.squad.isNotEmpty()) item(key = "leaders") { Block { Leaders(s.squad) { onTab(Tab.Squad) } } }
        s.table?.let { t ->
            val idx = t.rows.indexOfFirst { it.team.id == FCB_ESPN_ID }
            if (idx >= 0) item(key = "table") {
                val start = (idx - 2).coerceIn(0, maxOf(0, t.rows.size - 5))
                Block(pad = false) {
                    Column {
                        Heading(t.league.removePrefix("Spanish "), Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) { LinkText("Full table", { onTab(Tab.Table) }) }
                        StandingsHeader(compact = true)
                        t.rows.subList(start, minOf(t.rows.size, start + 5)).forEach { StandingsLine(it, compact = true) }
                    }
                }
            }
        }
        val upcoming = s.upcoming.drop(if (s.live == null) 1 else 0).take(4)
        if (upcoming.isNotEmpty()) item(key = "upcoming") {
            Block(pad = false) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    Heading("Coming up", Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) { LinkText("Calendar", { onTab(Tab.Matches) }) }
                    upcoming.forEachIndexed { i, m -> if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp)); MatchRow(m, onOpen, withMonth = true) }
                    Text("Times in ${java.time.ZoneId.systemDefault().id.substringAfter("/").replace('_', ' ')} time.", style = T.small, color = P.text3,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        if (s.news.isNotEmpty()) item(key = "news") { Block { LatestNews(s.news.take(3)) { onTab(Tab.News) } } }
        item(key = "footer") { Footer(s, state.error) }
    }
}

@Composable
private fun Block(pad: Boolean = true, content: @Composable () -> Unit) {
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Box(if (pad) Modifier.padding(16.dp) else Modifier) { content() } }
}

// ---------------------------------------------------------------- hero: the brand moment

@Composable
fun Hero(m: Match, live: MatchDetail?, onOpen: (Match) -> Unit) {
    CompositionLocalProvider(LocalPalette provides HeroPalette.copy(dark = P.dark)) {
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(PanelShape).background(P.heroBg)
                .blaugranaEdge().pressable({ onOpen(m) }, "Open match centre"),
        ) {
            Column(Modifier.padding(start = 26.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.isLive) { LiveFlag(); Spacer(Modifier.width(10.dp)) }
                    Text(Ui.compName(m), style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.accentText)
                    Text("  ·  ${if (m.isLive) Ui.clock(m) else Ui.dayLabel(m)}", style = T.label, color = P.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                m.note?.let { Text(it, style = T.small, color = P.text3, modifier = Modifier.padding(top = 2.dp)) }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeroSide(m.home, "Home", Modifier.weight(1f))
                    Box(Modifier.width(118.dp), contentAlignment = Alignment.Center) {
                        if (m.isUpcoming) Text("VS", style = T.display.copy(fontSize = 22.sp), color = P.text3)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            ScoreText(m, 60)
                            if (m.isLive) Text(Ui.clock(m), style = T.num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = P.loss)
                        }
                    }
                    HeroSide(m.away, "Away", Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                if (m.isUpcoming) Countdown(m)
                if (m.isLive && live != null) Scorers(live, m)
                Spacer(Modifier.height(14.dp))
                Hairline()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(R.drawable.ic_clock, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(Ui.longDate(m) + if (m.isUpcoming) ", ${Ui.time(m)}" else "", style = T.label, color = P.text2)
                }
                m.venue?.let {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(R.drawable.ic_map_pin, size = 16.dp); Spacer(Modifier.width(8.dp)); Text(it, style = T.label, color = P.text2)
                    }
                }
                m.tv?.let { Box(Modifier.padding(top = 2.dp)) { WatchIndia(it, onHero = true) } }
            }
        }
    }
}

@Composable
private fun HeroSide(t: TeamRef, tag: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(t, 72.dp)
        Spacer(Modifier.height(10.dp))
        Text(t.short.uppercase(), style = T.display.copy(fontSize = 24.sp, lineHeight = 24.sp), color = P.text, textAlign = TextAlign.Center, maxLines = 2)
        Text(tag.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp), color = P.text3)
    }
}

/** Days, hours and minutes to kick-off, ticking every 30 seconds. */
@Composable
private fun Countdown(m: Match) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(m.id) { while (true) { delay(30_000); now = Instant.now() } }
    val d = Duration.between(now, m.kickoff)
    if (d.isNegative) { Text("Kick-off", style = T.display.copy(fontSize = 22.sp), color = P.accentText); return }
    Row(Modifier.fillMaxWidth().semanticsLabel("Kick-off in ${d.toDays()} days, ${d.toHoursPart()} hours, ${d.toMinutesPart()} minutes"),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        listOf(d.toDays() to "Days", d.toHoursPart().toLong() to "Hrs", d.toMinutesPart().toLong() to "Min").forEach { (n, l) ->
            Column(Modifier.width(72.dp).clip(RoundedCornerShape(10.dp)).background(P.surface2).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d".format(n), style = T.display.copy(fontSize = 30.sp), color = P.text)
                Text(l.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp), color = P.text3)
            }
        }
    }
}

@Composable
fun Scorers(d: MatchDetail, m: Match) {
    val goals = d.events.filter { it.kind == "goal" || it.kind == "own-goal" }
    if (goals.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        goals.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.minute, Modifier.width(44.dp), style = T.num.copy(fontSize = 13.sp), color = P.text3)
                Icon(R.drawable.ic_soccer_ball, size = 14.dp, tint = if ((g.side == m.fcbSide)) P.accentText else P.text3)
                Spacer(Modifier.width(8.dp))
                val team = if (g.side == "home") m.home.short else m.away.short
                Text("${g.players.firstOrNull() ?: "Goal"}${if (g.kind == "own-goal") " (OG)" else if (g.penalty) " (pen)" else ""}",
                    style = T.label.copy(fontSize = 14.sp), color = P.text, modifier = Modifier.weight(1f))
                Text(team, style = T.small, color = P.text3)
            }
        }
    }
}

// ---------------------------------------------------------------- last result, form, leaders

@Composable
private fun LastResult(m: Match, d: MatchDetail?, onOpen: (Match) -> Unit) {
    Column {
        Heading("Last result") { LinkText("Match centre", { onOpen(m) }) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crest(m.home, 40.dp)
            Spacer(Modifier.width(12.dp))
            ScoreText(m, 52)
            Spacer(Modifier.width(12.dp))
            Crest(m.away, 40.dp)
            Spacer(Modifier.weight(1f))
            WdlChip(m.result, 30.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text("${if (m.fcbSide == "home") "vs" else "at"} ${m.opponent.name}  ·  ${Ui.compShort(m)}  ·  ${Ui.dayLabel(m)}", style = T.label, color = P.text2)
        if (d != null) {
            val ours = d.goalsFor(m.fcbSide)
            if (ours.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Hairline()
                Spacer(Modifier.height(10.dp))
                // Group each scorer's goals: "Raphinha 22', 52', 69'".
                ours.groupBy { it.players.firstOrNull() ?: "Own goal" }.forEach { (who, gs) ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(R.drawable.ic_soccer_ball, size = 15.dp, tint = P.accentText)
                        Spacer(Modifier.width(10.dp))
                        Text(who, style = T.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = P.text)
                        Spacer(Modifier.width(8.dp))
                        Text(gs.joinToString(", ") { it.minute + if (it.penalty) " pen" else "" }, style = T.num.copy(fontSize = 13.sp), color = P.text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormStrip(form: List<Match>, onOpen: (Match) -> Unit) {
    Column {
        Heading("Form")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            form.forEach { m ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).pressable({ onOpen(m) })
                    .semanticsLabel("${m.result} ${if (m.fcbSide == "home") "vs" else "at"} ${m.opponent.name}, ${m.scoreText}").padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(m.opponent, 32.dp)
                    Spacer(Modifier.height(6.dp))
                    Text("${m.fcb.score}-${m.opponent.score}", style = T.num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = P.text)
                    Spacer(Modifier.height(6.dp))
                    WdlChip(m.result, 24.dp)
                }
            }
        }
    }
}

@Composable
private fun Leaders(squad: List<Player>, onMore: () -> Unit) {
    Column {
        Heading("Leaders") { LinkText("Squad", onMore) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("Goals" to { p: Player -> p.stats.goals ?: 0 }, "Assists" to { p: Player -> p.stats.assists ?: 0 }).forEach { (label, stat) ->
                Column(Modifier.weight(1f)) {
                    Text(label.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold), color = P.text3)
                    Spacer(Modifier.height(8.dp))
                    squad.filter { stat(it) > 0 }.sortedWith(compareByDescending<Player> { stat(it) }.thenBy { it.stats.apps ?: 0 }).take(4)
                        .forEachIndexed { i, p ->
                            Row(Modifier.padding(vertical = 5.dp).semanticsLabel("${p.name}, ${stat(p)} ${label.lowercase()}"), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p, 34.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(p.short ?: p.name, Modifier.weight(1f), style = T.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                    color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${stat(p)}", style = T.display.copy(fontSize = if (i == 0) 28.sp else 22.sp), color = if (i == 0) P.accentText else P.text)
                            }
                        }
                }
            }
        }
    }
}

/** A player's round portrait (official photo when matched), or their initials. */
@Composable
fun Avatar(p: Player, size: androidx.compose.ui.unit.Dp) {
    RemoteImage(p.headshot, Modifier.size(size).clip(CircleShape), maxPx = 240, crop = Alignment.TopCenter) {
        Text(p.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
            style = T.label.copy(fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Bold), color = P.text2)
    }
}

// ---------------------------------------------------------------- news, footer, skeleton

@Composable
private fun LatestNews(items: List<NewsItem>, onMore: () -> Unit) {
    val context = LocalContext.current
    Column {
        Heading("Latest") { LinkText("All news", onMore) }
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, n ->
            if (i > 0) Hairline(Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth().pressable({ context.openUrl(n.url) }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(n.source, Ui.ago(n.published).takeIf { n.published != null }).joinToString("  ·  "), style = T.small, color = P.text3)
                    Spacer(Modifier.height(3.dp))
                    Text(n.title, style = T.label.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), color = P.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (n.image != null) {
                    Spacer(Modifier.width(12.dp))
                    RemoteImage(n.image, Modifier.width(92.dp).aspectRatio(4 / 3f).clip(RoundedCornerShape(10.dp)), maxPx = 320)
                }
            }
        }
    }
}

@Composable
private fun Footer(s: Snapshot, error: String?) {
    val problems = s.errors.keys.map { it.substringBefore("-").replaceFirstChar { c -> c.uppercase() } }.distinct()
    Text(
        "Updated ${Ui.ago(s.updatedAt)}, straight from ESPN, fcbarcelona.com, LiveSoccerTV, FanCode and Google News." +
            (if (problems.isNotEmpty()) " Couldn't reach ${problems.joinToString(", ")} this time." else "") + (error?.let { " $it" } ?: ""),
        style = T.small, color = P.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun OverviewSkeleton(state: UiState) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(330.dp).clip(PanelShape).background(P.heroBg).blaugranaEdge()) {
            Column(Modifier.padding(start = 26.dp, top = 20.dp, end = 18.dp)) {
                Skeleton(Modifier.width(160.dp).height(14.dp))
                Spacer(Modifier.height(26.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Skeleton(Modifier.size(72.dp), CircleShape); Skeleton(Modifier.size(72.dp), CircleShape)
                }
                Spacer(Modifier.height(24.dp))
                Skeleton(Modifier.fillMaxWidth().height(56.dp))
            }
        }
        Skeleton(Modifier.fillMaxWidth().height(150.dp), PanelShape)
        Skeleton(Modifier.fillMaxWidth().height(120.dp), PanelShape)
        if (!state.refreshing && state.error != null) StateNote(R.drawable.ic_wifi_slash, "${state.error}. Pull down to try again.")
    }
}
