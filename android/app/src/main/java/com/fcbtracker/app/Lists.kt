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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.R
import com.fcbtracker.core.Match
import com.fcbtracker.core.NewsItem
import com.fcbtracker.core.Player
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

// ---------------------------------------------------------------- matches

@Composable
fun MatchesScreen(state: UiState, onRefresh: () -> Unit, onOpen: (Match) -> Unit) {
    val s = state.snapshot
    var fixtures by rememberSaveable { mutableStateOf(true) }
    var comp by rememberSaveable { mutableStateOf<String?>(null) }
    Screen(state.refreshing, onRefresh) {
        if (s == null) { item { SkeletonList(8) }; return@Screen }
        val played = s.played.reversed(); val upcoming = s.withTv.filter { !it.isPlayed }
        item {
            PageHead("Matches", "${played.size} played, ${upcoming.size} to come. Times in ${ZoneId.systemDefault().id.substringAfter("/").replace('_', ' ')}.")
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(PillShape).background(P.surface).border(1.dp, P.line, PillShape).padding(4.dp)) {
                listOf(true to "Fixtures", false to "Results").forEach { (f, l) ->
                    val sel = fixtures == f
                    Box(Modifier.weight(1f).clip(PillShape).background(if (sel) P.accent else P.surface).pressable({ fixtures = f }).padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(l, style = T.label.copy(fontWeight = FontWeight.SemiBold), color = if (sel) P.onAccent else P.text2)
                    }
                }
            }
        }
        val list = if (fixtures) upcoming else played
        val comps = list.map { it.competition.slug to Ui.compShort(it) }.distinct()
        if (comps.size > 1) item {
            ChipRow(Modifier.padding(top = 12.dp)) {
                Chip("All", comp == null) { comp = null }
                comps.forEach { (slug, name) -> Chip(name, comp == slug) { comp = slug } }
            }
        }
        val shown = list.filter { comp == null || it.competition.slug == comp }
        if (shown.isEmpty()) item { StateNote(R.drawable.ic_calendar_x, if (fixtures) "No fixtures scheduled for this competition yet." else "No results for this competition yet.") }
        shown.groupBy { it.kickoff.atZone(ZoneId.systemDefault()).format(monthFmt) }.forEach { (month, ms) ->
            item(key = "h-$month-$fixtures") {
                Text(month.uppercase(), style = T.display.copy(fontSize = 17.sp, letterSpacing = 0.6.sp), color = P.text2,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
            }
            item(key = "g-$month-$fixtures") {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Column { ms.forEachIndexed { i, m -> if (i > 0) Hairline(Modifier.padding(horizontal = 16.dp)); MatchRow(m, onOpen) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- squad

private val GROUPS = listOf("G" to "Goalkeepers", "D" to "Defenders", "M" to "Midfielders", "F" to "Forwards")
private enum class Sort(val label: String) { Number("Number"), Goals("Goals"), Assists("Assists"), Apps("Appearances"), Age("Age") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadScreen(state: UiState, onRefresh: () -> Unit) {
    val squad = state.snapshot?.squad.orEmpty()
    var sort by rememberSaveable { mutableStateOf(Sort.Number) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    Screen(state.refreshing, onRefresh) {
        item { PageHead("Squad", if (squad.isEmpty()) null else "${squad.size} players. League stats from ESPN, photos from fcbarcelona.com.") }
        if (squad.isEmpty()) {
            item { if (state.refreshing || state.snapshot == null) SkeletonList(6) else StateNote(R.drawable.ic_users_three, "The squad hasn't loaded yet. Pull down to refresh.") }
            return@Screen
        }
        item {
            ChipRow(Modifier.padding(bottom = 4.dp)) { Sort.entries.forEach { Chip(it.label, sort == it) { sort = it } } }
        }
        val cmp: Comparator<Player> = when (sort) {
            Sort.Number -> compareBy { it.number?.toIntOrNull() ?: 99 }
            Sort.Goals -> compareByDescending<Player> { it.stats.goals ?: 0 }.thenBy { it.number?.toIntOrNull() ?: 99 }
            Sort.Assists -> compareByDescending<Player> { it.stats.assists ?: 0 }.thenBy { it.number?.toIntOrNull() ?: 99 }
            Sort.Apps -> compareByDescending<Player> { it.stats.apps ?: 0 }.thenBy { it.number?.toIntOrNull() ?: 99 }
            Sort.Age -> compareBy { it.age ?: 99 }
        }
        GROUPS.forEach { (code, title) ->
            val list = squad.filter { it.pos == code }.sortedWith(cmp)
            if (list.isEmpty()) return@forEach
            item(key = "g-$code") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(title.uppercase(), style = T.display.copy(fontSize = 20.sp, letterSpacing = 0.6.sp), color = P.text)
                    Spacer(Modifier.width(8.dp))
                    Text("${list.size}", style = T.num.copy(fontSize = 13.sp), color = P.text3, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            items(list, key = { it.id }) { p -> PlayerCard(p) { openId = p.id } }
        }
    }
    squad.find { it.id == openId }?.let { p ->
        ModalBottomSheet(onDismissRequest = { openId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = P.surface, contentColor = P.text) { PlayerSheet(p) }
    }
}

private fun statCells(p: Player): List<Pair<String, Int?>> =
    if (p.pos == "G") listOf("Apps" to p.stats.apps, "Saves" to p.stats.saves, "Conceded" to p.stats.conceded, "Yellow" to p.stats.yellow)
    else listOf("Apps" to p.stats.apps, "Goals" to p.stats.goals, "Assists" to p.stats.assists, "Shots" to p.stats.shots)

@Composable
private fun PlayerCard(p: Player, onOpen: () -> Unit) {
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.pressable(onOpen, "Show ${p.name}").padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = T.label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        p.flag?.let { RemoteImage(it, Modifier.size(width = 16.dp, height = 11.dp).clip(RoundedCornerShape(2.dp)), maxPx = 64); Spacer(Modifier.width(6.dp)) }
                        Text(listOfNotNull(p.nationality, p.age?.let { "$it" }).joinToString(", "), style = T.small, color = P.text2, maxLines = 1)
                    }
                    if (p.injured) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(R.drawable.ic_first_aid, size = 13.dp, tint = P.loss); Spacer(Modifier.width(4.dp))
                        Text("Injured", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = P.loss)
                    }
                }
                Text(p.number ?: "", style = T.display.copy(fontSize = 38.sp), color = P.text3, modifier = Modifier.semanticsLabel("Shirt number ${p.number ?: "unknown"}"))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(P.surface2).padding(vertical = 8.dp)) {
                statCells(p).forEach { (l, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v?.toString() ?: "-", style = T.num.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = if (v == null) P.text3 else P.text)
                        Text(l, style = T.small.copy(fontSize = 11.sp), color = P.text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSheet(p: Player) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p, 96.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(p.number ?: "", style = T.display.copy(fontSize = 30.sp), color = P.accentText)
                Text(p.name.uppercase(), style = T.display.copy(fontSize = 28.sp, lineHeight = 28.sp), color = P.text)
                Text(listOfNotNull(p.posName, p.nationality, p.age?.let { "$it years" }).joinToString("  ·  "), style = T.label, color = P.text2)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("THIS SEASON IN LALIGA", style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold), color = P.text3)
        Spacer(Modifier.height(8.dp))
        val s = p.stats
        val cells = listOf("Appearances" to s.apps, "Off the bench" to s.subIns, "Goals" to s.goals, "Assists" to s.assists,
            "Shots" to s.shots, "On target" to s.shotsOnTarget, "Fouls" to s.fouls, "Yellow cards" to s.yellow) +
            (if (p.pos == "G") listOf("Saves" to s.saves, "Conceded" to s.conceded) else emptyList())
        cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, v) ->
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(P.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(l, Modifier.weight(1f), style = T.label, color = P.text2)
                        Text(v?.toString() ?: "-", style = T.num.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = P.text)
                    }
                }
            }
        }
        p.profile?.let { url -> Box(Modifier.padding(top = 8.dp)) { LinkText("Official profile on fcbarcelona.com", { context.openUrl(url) }, R.drawable.ic_arrow_square_out) } }
    }
}

// ---------------------------------------------------------------- table

@Composable
fun TableScreen(state: UiState, onRefresh: () -> Unit) {
    val t = state.snapshot?.table
    Screen(state.refreshing, onRefresh) {
        item { PageHead(t?.league?.removePrefix("Spanish ") ?: "Table", state.snapshot?.position?.let { "Barcelona are ${it.rank}${Ui.ordinal(it.rank)} with ${it.points} points from ${it.played} games." }) }
        if (t == null) { item { SkeletonList(10) }; return@Screen }
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    StandingsHeader()
                    t.rows.forEachIndexed { i, r -> if (i > 0) Hairline(); StandingsLine(r) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- news

@Composable
fun NewsScreen(state: UiState, onRefresh: () -> Unit) {
    val news = state.snapshot?.news.orEmpty()
    var origin by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Screen(state.refreshing, onRefresh) {
        item { PageHead("News", "From fcbarcelona.com, Google News and ESPN.") }
        if (news.isEmpty()) { item { if (state.refreshing || state.snapshot == null) SkeletonList(6) else StateNote(R.drawable.ic_newspaper, "No news loaded yet. Pull down to refresh.") }; return@Screen }
        item {
            ChipRow(Modifier.padding(bottom = 8.dp)) {
                listOf(null to "All", "official" to "Club", "google" to "Google News", "espn" to "ESPN").forEach { (o, l) ->
                    if (o == null || news.any { it.origin == o }) Chip(l, origin == o) { origin = o }
                }
            }
        }
        val shown = news.filter { origin == null || it.origin == origin }
        shown.firstOrNull { it.image != null }?.let { lead ->
            item(key = "lead-${lead.url}") { LeadStory(lead) { context.openUrl(lead.url) } }
        }
        val lead = shown.firstOrNull { it.image != null }
        items(shown.filter { it != lead }, key = { it.url }) { n -> NewsRow(n) { context.openUrl(n.url) } }
    }
}

@Composable
private fun LeadStory(n: NewsItem, onOpen: () -> Unit) {
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.pressable(onOpen, "Open story")) {
            RemoteImage(n.image, Modifier.fillMaxWidth().aspectRatio(16 / 9f), maxPx = 900)
            Column(Modifier.padding(16.dp)) {
                Text(listOfNotNull(n.source, n.published?.let { Ui.ago(it) }).joinToString("  ·  "), style = T.small, color = P.accentText)
                Spacer(Modifier.height(6.dp))
                Text(n.title, style = T.display.copy(fontSize = 26.sp, lineHeight = 27.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = P.text)
                n.summary?.let { Spacer(Modifier.height(6.dp)); Text(it, style = T.body.copy(fontSize = 14.sp), color = P.text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun NewsRow(n: NewsItem, onOpen: () -> Unit) {
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.pressable(onOpen, "Open story").padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(n.source, n.published?.let { Ui.ago(it) }).joinToString("  ·  "), style = T.small, color = P.text3, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(n.title, style = T.label.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = P.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                n.summary?.let { Spacer(Modifier.height(4.dp)); Text(it, style = T.small.copy(lineHeight = 17.sp), color = P.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (n.image != null) {
                Spacer(Modifier.width(12.dp))
                RemoteImage(n.image, Modifier.width(96.dp).aspectRatio(1f).clip(RoundedCornerShape(10.dp)), maxPx = 320)
            }
        }
    }
}
