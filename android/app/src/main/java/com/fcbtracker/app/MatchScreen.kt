package com.fcbtracker.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.fcbtracker.core.MatchEvent
import com.fcbtracker.core.TeamRef
import com.fcbtracker.core.TeamStat

private enum class MTab(val label: String) { Preview("Preview"), Summary("Summary"), Stats("Stats"), Lineups("Lineups") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatchScreen(m0: Match, state: UiState, onBack: () -> Unit, onOpen: (Match) -> Unit) {
    val d = state.detail?.takeIf { it.match.id == m0.id }
    val m = d?.match?.let { it.copy(tv = it.tv ?: m0.tv) } ?: m0
    val tabs = if (m.isUpcoming) listOf(MTab.Preview, MTab.Lineups) else listOf(MTab.Summary, MTab.Stats, MTab.Lineups)
    var tab by rememberSaveable(m0.id) { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()

    LazyColumn(Modifier.fillMaxSize().background(P.bg).statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.pressable(onBack, "Back").padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_arrow_left, size = 20.dp, tint = P.text2)
                Spacer(Modifier.width(8.dp))
                Text("Back", style = T.label.copy(fontSize = 15.sp), color = P.text2)
            }
        }
        item { Scoreboard(m, d?.venue ?: m.venue, d?.attendance, d?.referee) }
        stickyHeader {
            Row(Modifier.fillMaxWidth().background(P.bg).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().clip(PillShape).background(P.surface).border(1.dp, P.line, PillShape).padding(4.dp)) {
                    tabs.forEach { t ->
                        val sel = t == tab
                        Box(Modifier.weight(1f).clip(PillShape).background(if (sel) P.accent else P.surface).pressable({ tab = t }).padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text(t.label, style = T.label.copy(fontWeight = FontWeight.SemiBold), color = if (sel) P.onAccent else P.text2)
                        }
                    }
                }
            }
        }
        when {
            tab == MTab.Preview -> item { Preview(m, state, onOpen) }
            d == null && state.detailLoading -> item { SkeletonList(5) }
            d == null -> item { StateNote(R.drawable.ic_wifi_slash, state.detailError?.let { "Couldn't load the match: $it. Go back and open it again." } ?: "No details yet.") }
            tab == MTab.Summary -> {
                if (d.events.isEmpty()) item { StateNote(R.drawable.ic_clock, if (m.isLive) "No key events yet." else "ESPN has no key events for this match.") }
                else items(d.events) { e -> EventRow(e, m) }
            }
            tab == MTab.Stats -> if (d.stats.isEmpty()) item { StateNote(R.drawable.ic_chart_bar, "Team stats appear once the match starts.") } else item { Stats(d.stats, m) }
            else -> if (d.lineups.isEmpty()) item {
                StateNote(R.drawable.ic_users_three, if (m.isUpcoming) "No lineups yet. They're usually published about an hour before kick-off." else "Lineups weren't published for this match.")
            } else listOf("home", "away").forEach { side ->
                d.lineups[side]?.let { lu ->
                    val team = if (side == "home") m.home else m.away
                    item { LineupPitch(team, lu, d.events, team.id == FCB_ESPN_ID) }
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(m: Match, venue: String?, attendance: Int?, referee: String?) {
    CompositionLocalProvider(LocalPalette provides HeroPalette.copy(dark = P.dark)) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(PanelShape).background(P.heroBg).blaugranaEdge()) {
            Column(Modifier.padding(start = 26.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.isLive) { LiveFlag(); Spacer(Modifier.width(10.dp)) }
                    Text(Ui.compName(m), style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.accentText)
                    m.note?.let { Text("  ·  $it", style = T.label, color = P.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Side(m.home, Modifier.weight(1f))
                    Column(Modifier.width(124.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (m.isUpcoming) {
                            Text(Ui.time(m), style = T.display.copy(fontSize = 36.sp), color = P.text, maxLines = 1, softWrap = false)
                            Text(Ui.countdown(m), style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.accentText)
                        } else {
                            ScoreText(m, 64)
                            Text(if (m.isLive) Ui.clock(m) else m.status.short ?: "FT", style = T.num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = if (m.isLive) P.loss else P.text3)
                            if (m.home.shootout != null) Text("Pens ${m.home.shootout}-${m.away.shootout}", style = T.small, color = P.text2)
                        }
                    }
                    Side(m.away, Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Hairline()
                Spacer(Modifier.height(10.dp))
                Fact(R.drawable.ic_calendar_blank, Ui.longDate(m) + if (m.isUpcoming) ", ${Ui.time(m)}" else "")
                venue?.let { Fact(R.drawable.ic_map_pin, it) }
                attendance?.let { Fact(R.drawable.ic_users, "%,d fans".format(it)) }
                referee?.let { Fact(R.drawable.ic_flag, "Referee $it") }
                if (!m.isPlayed) m.tv?.let { WatchIndia(it, onHero = true) }
            }
        }
    }
}

@Composable
private fun Fact(icon: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, size = 16.dp); Spacer(Modifier.width(8.dp)); Text(text, style = T.label, color = P.text2)
    }
}

@Composable
private fun Side(t: TeamRef, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(t, 64.dp)
        Spacer(Modifier.height(8.dp))
        // Long single words ("BARCELONA") shrink rather than break mid-word on narrow phones.
        val size = when { t.short.length > 11 -> 17; t.short.length > 8 -> 19; else -> 22 }
        Text(t.short.uppercase(), style = T.display.copy(fontSize = size.sp, lineHeight = size.sp), color = P.text, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// ---------------------------------------------------------------- summary timeline

@Composable
private fun EventRow(e: MatchEvent, m: Match) {
    if (e.kind == "period") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Hairline(Modifier.weight(1f))
            Text(e.label.uppercase(), style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold), color = P.text3,
                modifier = Modifier.padding(horizontal = 12.dp))
            Hairline(Modifier.weight(1f))
        }
        return
    }
    val home = e.side == "home"
    val ours = (e.side == m.fcbSide)
    val isGoal = e.kind == "goal" || e.kind == "own-goal"
    val (title, sub) = when (e.kind) {
        "goal" -> (e.players.firstOrNull() ?: "Goal") to listOfNotNull(if (e.penalty) "Penalty" else null, e.players.getOrNull(1)?.let { "Assist $it" }).joinToString(", ").ifEmpty { null }
        "own-goal" -> (e.players.firstOrNull() ?: "Own goal") to "Own goal"
        "sub" -> (e.players.firstOrNull() ?: "Substitution") to e.players.getOrNull(1)?.let { "Off $it" }
        "yellow" -> (e.players.firstOrNull() ?: "") to "Yellow card"
        "red" -> (e.players.firstOrNull() ?: "") to e.label
        "pen-miss" -> (e.players.firstOrNull() ?: "") to e.label
        else -> e.label to null
    }
    val body = @Composable {
        Column(horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
            Text(title, style = T.label.copy(fontSize = 14.sp, fontWeight = if (isGoal) FontWeight.Bold else FontWeight.Medium),
                color = P.text, textAlign = if (home) TextAlign.End else TextAlign.Start)
            sub?.let { Text(it, style = T.small, color = P.text3, textAlign = if (home) TextAlign.End else TextAlign.Start) }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = if (isGoal) 60.dp else 52.dp).height(IntrinsicSize.Min).padding(horizontal = 16.dp).semanticsLabel("${e.minute} ${sub ?: e.label}, $title"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { if (home) body() }
        Box(Modifier.width(72.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(P.line))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(P.bg).padding(vertical = 2.dp)) {
                EventMarker(e, ours)
                Text(e.minute, style = T.num.copy(fontSize = 11.sp), color = P.text3)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (!home) body() }
    }
}

@Composable
private fun EventMarker(e: MatchEvent, ours: Boolean) {
    when (e.kind) {
        "goal", "own-goal" -> Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(if (ours) P.accent else P.surface3), contentAlignment = Alignment.Center) {
            Icon(R.drawable.ic_soccer_ball_fill, size = 16.dp, tint = if (ours) P.onAccent else P.text)
        }
        "yellow", "red" -> Box(Modifier.size(width = 13.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(if (e.kind == "red") Color(0xFFE5324A) else Color(0xFFF2C94C)))
        "sub" -> Row { Icon(R.drawable.ic_arrow_up, size = 14.dp, tint = P.win); Icon(R.drawable.ic_arrow_down, size = 14.dp, tint = P.loss) }
        else -> Icon(R.drawable.ic_x, size = 16.dp, tint = P.text3)
    }
}

// ---------------------------------------------------------------- stats: mirrored bars from a shared centre

@Composable
private fun Stats(stats: List<TeamStat>, m: Match) {
    val reduce = reducedMotion()
    val grow = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(450)) }
    val rates = stats.filter { it.type == "pct" }; val counts = stats.filter { it.type != "pct" }
    Panel(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(m.home, 22.dp); Spacer(Modifier.width(8.dp))
                Text(m.home.short, Modifier.weight(1f), style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.text)
                Text(m.away.short, style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.text); Spacer(Modifier.width(8.dp)); Crest(m.away, 22.dp)
            }
            listOf(rates, counts).filter { it.isNotEmpty() }.forEachIndexed { gi, group ->
                if (gi > 0) Hairline(Modifier.padding(top = 14.dp))
                group.forEach { s -> StatBar(s, m, grow.value) }
            }
        }
    }
}

@Composable
private fun StatBar(s: TeamStat, m: Match, grow: Float) {
    val h = s.home; val a = s.away
    fun fmt(v: Double?) = when { v == null -> "-"; s.type == "pct" -> "${if (v % 1.0 == 0.0) v.toInt() else v}%"; else -> "${v.toInt()}" }
    val total = ((h ?: 0.0) + (a ?: 0.0)).takeIf { it > 0 } ?: 1.0
    val homeLead = (h ?: 0.0) > (a ?: 0.0); val awayLead = (a ?: 0.0) > (h ?: 0.0)
    // Lower is better for these: the "leading" side is the smaller number.
    val lowerBetter = s.label in setOf("Fouls", "Yellow cards", "Red cards", "Offsides")
    val homeGood = if (lowerBetter) awayLead else homeLead; val awayGood = if (lowerBetter) homeLead else awayLead
    Column(Modifier.padding(top = 14.dp).semanticsLabel("${s.label}: ${m.home.short} ${fmt(h)}, ${m.away.short} ${fmt(a)}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(h), Modifier.width(64.dp), style = T.num.copy(fontSize = 15.sp, fontWeight = if (homeGood) FontWeight.SemiBold else FontWeight.Medium), color = if (homeGood) P.text else P.text2)
            Text(s.label, Modifier.weight(1f), style = T.label, color = P.text2, textAlign = TextAlign.Center)
            Text(fmt(a), Modifier.width(64.dp), style = T.num.copy(fontSize = 15.sp, fontWeight = if (awayGood) FontWeight.SemiBold else FontWeight.Medium), color = if (awayGood) P.text else P.text2, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth(((h ?: 0.0) / total).toFloat() * grow).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (homeGood) P.accent else P.draw))
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(((a ?: 0.0) / total).toFloat() * grow).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (awayGood) P.accent else P.draw))
            }
        }
    }
}

// ---------------------------------------------------------------- preview: position, form, head-to-head

@Composable
private fun Preview(m: Match, state: UiState, onOpen: (Match) -> Unit) {
    val s = state.snapshot
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val rows = s?.table?.rows.orEmpty()
        val pair = listOfNotNull(rows.find { it.team.id == m.home.id }, rows.find { it.team.id == m.away.id }).sortedBy { it.rank }
        if (pair.size == 2) Panel {
            Column {
                Heading("League position", Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
                StandingsHeader(compact = true); pair.forEachIndexed { i, r -> if (i > 0) Hairline(); StandingsLine(r, compact = true) }
            }
        }
        s?.form?.takeIf { it.isNotEmpty() }?.let { form ->
            Panel {
                Column(Modifier.padding(16.dp)) {
                    Heading("Barcelona form")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        form.forEach { f ->
                            Column(Modifier.clip(RoundedCornerShape(12.dp)).pressable({ onOpen(f) }).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Crest(f.opponent, 30.dp); Spacer(Modifier.height(4.dp))
                                Text("${f.fcb.score}-${f.opponent.score}", style = T.num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = P.text)
                                Spacer(Modifier.height(4.dp)); WdlChip(f.result, 22.dp)
                            }
                        }
                    }
                }
            }
        }
        Panel {
            Column(Modifier.padding(vertical = 14.dp)) {
                Heading("Meetings since 2020", Modifier.padding(horizontal = 16.dp))
                val ms = state.meetings
                when {
                    ms == null -> Box(Modifier.padding(16.dp)) { Skeleton(Modifier.fillMaxWidth().height(64.dp)) }
                    ms.isEmpty() -> Text("No meetings with ${m.opponent.name} since 2020.", style = T.body, color = P.text2, modifier = Modifier.padding(16.dp))
                    else -> {
                        val gf = ms.sumOf { it.fcb.score ?: 0 }; val ga = ms.sumOf { it.opponent.score ?: 0 }
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Played" to "${ms.size}", "Won" to "${ms.count { it.result == "W" }}", "Drawn" to "${ms.count { it.result == "D" }}",
                                "Lost" to "${ms.count { it.result == "L" }}", "Goals" to "$gf:$ga").forEach { (l, v) ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, P.line, RoundedCornerShape(10.dp)).padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(v, style = T.display.copy(fontSize = 24.sp), color = P.text)
                                    Text(l, style = T.small.copy(fontSize = 11.sp), color = P.text3)
                                }
                            }
                        }
                        ms.groupBy { Ui.season(it) }.forEach { (season, games) ->
                            Text(season, style = T.num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = P.text3, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                            games.forEach { MatchRow(it, onOpen, showTv = false, withMonth = true) }
                        }
                    }
                }
            }
        }
    }
}
