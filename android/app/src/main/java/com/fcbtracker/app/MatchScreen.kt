package com.fcbtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.core.FCB_ESPN_ID
import com.fcbtracker.core.Match
import com.fcbtracker.core.MatchEvent
import com.fcbtracker.core.TeamStat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(m0: Match, state: UiState, onBack: () -> Unit, onOpen: (Match) -> Unit) {
    val d = state.detail?.takeIf { it.match.id == m0.id }
    val m = d?.match ?: m0
    var tab by rememberSaveable(m0.id) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().background(P.bg).statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("‹  Back", color = P.text2, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 20.dp, vertical = 16.dp))
        }
        item { Scoreboard(m, d?.venue ?: m.venue, d?.attendance, d?.referee) }
        item {
            val labels = listOf("Summary", "Stats", "Lineups")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                labels.forEachIndexed { i, l ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
                }
            }
        }
        when {
            d == null && state.detailLoading -> item { Note("Loading from ESPN...") }
            d == null -> item { Note(state.detailError?.let { "Couldn't load: $it" } ?: "No details yet.") }
            tab == 0 -> {
                val events = d.events.filter { it.kind != "sub" || m.isLive || m.isPlayed }
                if (events.isEmpty()) item { Note(if (m.isUpcoming) "Kick-off ${Ui.dayLabel(m)}. Events appear here once it starts." else "No key events.") }
                else events.forEach { e -> item { EventRow(e) } }
            }
            tab == 1 -> if (d.stats.isEmpty()) item { Note("Team stats appear once the match starts.") } else item { Stats(d.stats, m) }
            else -> if (d.lineups.isEmpty()) item { Note(if (m.isUpcoming) "Lineups are usually published about an hour before kick-off." else "Lineups weren't published.") }
            else listOf("home", "away").forEach { side ->
                d.lineups[side]?.let { lu ->
                    val team = if (side == "home") m.home else m.away
                    item { LineupPitch(team, lu, d.events, team.id == FCB_ESPN_ID) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = P.text2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))

@Composable
private fun Scoreboard(m: Match, venue: String?, attendance: Int?, referee: String?) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.isLive) { LiveFlag(); Spacer(Modifier.width(8.dp)) }
                Text(Ui.compName(m) + (m.note?.let { "  ·  $it" } ?: ""), color = P.text2, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Side(m.home, Modifier.weight(1f))
                Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m.isUpcoming) {
                        Text(Ui.time(m), color = P.text, fontFamily = Display, fontSize = 30.sp, maxLines = 1, softWrap = false)
                        Text(Ui.countdown(m), color = P.gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("${m.home.score ?: 0}-${m.away.score ?: 0}", color = P.text, fontFamily = Display, fontSize = 48.sp)
                        Text(if (m.isLive) Ui.clock(m) else m.status.detail ?: "FT", color = if (m.isLive) P.live else P.text2, fontSize = 13.sp)
                        if (m.home.shootout != null) Text("Pens ${m.home.shootout}-${m.away.shootout}", color = P.text2, fontSize = 12.sp)
                    }
                }
                Side(m.away, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            val facts = listOfNotNull(Ui.longDate(m), venue, attendance?.let { "%,d fans".format(it) }, referee?.let { "Referee $it" })
            Text(facts.joinToString("  ·  "), color = P.text2, fontSize = 12.sp, textAlign = TextAlign.Center)
            if (!m.isPlayed) m.tv?.let { Spacer(Modifier.height(14.dp)); WatchIndia(it) }
        }
    }
}

@Composable
private fun Side(t: com.fcbtracker.core.TeamRef, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(t, 60.dp)
        Spacer(Modifier.height(8.dp))
        Text(t.short, color = P.text, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2,
            fontWeight = if (t.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun EventRow(e: MatchEvent) {
    val (mark, color) = when (e.kind) {
        "goal" -> (if (e.penalty) "PEN" else "GOAL") to P.gold
        "own-goal" -> "OG" to P.gold
        "yellow" -> "" to Color(0xFFF2C94C)
        "red" -> "" to P.loss
        "pen-miss" -> "MISS" to P.text2
        "sub" -> "SUB" to P.text2
        else -> "" to P.text2
    }
    if (e.kind == "period") {
        Text(e.label, color = P.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        return
    }
    val home = e.side == "home"
    val body = @Composable {
        Column(horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
            Text(e.players.firstOrNull() ?: e.label, color = P.text, fontSize = 14.sp,
                fontWeight = if (e.kind == "goal" || e.kind == "own-goal") FontWeight.SemiBold else FontWeight.Normal)
            val extra = when (e.kind) {
                "goal" -> e.players.getOrNull(1)?.let { "Assist $it" }
                "sub" -> e.players.getOrNull(1)?.let { "Off $it" }
                else -> null
            }
            extra?.let { Text(it, color = P.text2, fontSize = 12.sp) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { if (home) body() }
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(e.minute, color = P.text2, fontSize = 12.sp)
            if (e.kind == "yellow" || e.kind == "red") Box(Modifier.size(10.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(color))
            else Text(mark, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (!home) body() }
    }
}

@Composable
private fun Stats(stats: List<TeamStat>, m: Match) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row { Text(m.home.short, Modifier.weight(1f), color = P.text2, fontSize = 12.sp); Text(m.away.short, color = P.text2, fontSize = 12.sp) }
            stats.forEach { s ->
                val h = s.home ?: 0.0; val a = s.away ?: 0.0
                val fmt = { v: Double -> if (s.type == "pct") "${if (v % 1.0 == 0.0) v.toInt() else v}%" else "${v.toInt()}" }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(h), color = P.text, fontWeight = if (h > a) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                        Text(s.label, Modifier.weight(1f), color = P.text2, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Text(fmt(a), color = P.text, fontWeight = if (a > h) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    val total = (h + a).takeIf { it > 0 } ?: 1.0
                    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Bar((h / total).toFloat(), if (m.home.id == FCB_ESPN_ID) P.gold else P.draw, alignEnd = true)
                        Bar((a / total).toFloat(), if (m.away.id == FCB_ESPN_ID) P.gold else P.draw, alignEnd = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Bar(frac: Float, color: Color, alignEnd: Boolean) {
    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(P.surface2),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}
