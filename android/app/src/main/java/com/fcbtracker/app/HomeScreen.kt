package com.fcbtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.core.FCB_ESPN_ID
import com.fcbtracker.core.Match
import com.fcbtracker.core.Snapshot
import com.fcbtracker.core.TableRow
import com.fcbtracker.core.Tv
import com.fcbtracker.core.TvListing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val card = RoundedCornerShape(18.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, tab: Int, onTab: (Int) -> Unit, onRefresh: () -> Unit, onOpen: (Match) -> Unit) {
    val s = state.snapshot
    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding()) {
        TopBar()
        val tabs = listOf("Overview", "Matches", "Table")
        TabRow(
            selectedTabIndex = tab, containerColor = P.bg, contentColor = P.text,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = P.gold) },
            divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(P.line)) },
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) },
                    selectedContentColor = P.text, unselectedContentColor = P.text2)
            }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            when {
                s == null || s.matches.isEmpty() -> Empty(state)
                tab == 0 -> Overview(s, state, onOpen)
                tab == 1 -> Matches(s, onOpen)
                else -> FullTable(s)
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(6.dp).height(28.dp).clip(RoundedCornerShape(3.dp))) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Blau)); Box(Modifier.weight(1f).fillMaxWidth().background(Grana))
        }
        Spacer(Modifier.width(12.dp))
        Text(buildAnnotatedString {
            append("FCB "); withStyle(SpanStyle(color = P.gold)) { append("TRACKER") }
        }, fontFamily = Display, fontSize = 26.sp, color = P.text, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun Empty(state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item {
            Text(if (state.refreshing) "Fetching from ESPN, LiveSoccerTV and FanCode..." else state.error ?: "Pull down to load.",
                color = P.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier = modifier, color = P.text2, fontFamily = Display, fontSize = 15.sp, letterSpacing = 1.2.sp)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(card).background(P.surface).border(1.dp, P.line, card)) { content() }
}

@Composable
private fun Overview(s: Snapshot, state: UiState, onOpen: (Match) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        (s.live ?: s.next)?.let { m -> item(key = "hero") { Hero(m, onOpen) } }
        s.last?.let { last ->
            item(key = "last") {
                Card(Modifier.clickable { onOpen(last) }) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Last result")
                        Spacer(Modifier.height(10.dp))
                        MatchLine(last)
                    }
                }
            }
        }
        if (s.form.isNotEmpty()) item(key = "form") {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Form")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        s.form.forEach { m ->
                            Column(Modifier.clickable { onOpen(m) }.semantics { contentDescription = "${m.result} ${m.opponent.name} ${m.scoreText}" },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Crest(m.opponent, 30.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(m.scoreText ?: "", color = P.text, fontFamily = Display, fontSize = 16.sp)
                                Spacer(Modifier.height(6.dp))
                                ResultBadge(m.result)
                            }
                        }
                    }
                }
            }
        }
        val upcoming = s.upcoming.drop(if (s.live == null) 1 else 0).take(5)
        if (upcoming.isNotEmpty()) item(key = "up") {
            Card {
                Column(Modifier.padding(vertical = 16.dp)) {
                    SectionTitle("Coming up", Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(4.dp))
                    upcoming.forEach { MatchRow(it, onOpen) }
                }
            }
        }
        s.table?.let { t ->
            val idx = t.rows.indexOfFirst { it.team.id == FCB_ESPN_ID }
            if (idx >= 0) item(key = "table") {
                val start = (idx - 2).coerceIn(0, maxOf(0, t.rows.size - 5))
                Card {
                    Column(Modifier.padding(vertical = 16.dp)) {
                        SectionTitle(t.league.removePrefix("Spanish "), Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(8.dp))
                        TableHeader()
                        t.rows.subList(start, minOf(t.rows.size, start + 5)).forEach { TableLine(it) }
                    }
                }
            }
        }
        item(key = "status") {
            val problems = s.errors.keys.joinToString(", ")
            Text("Updated ${Ui.ago(s.updatedAt)} straight from ESPN, LiveSoccerTV and FanCode." +
                    (if (problems.isNotEmpty()) " Couldn't reach: $problems." else "") + (state.error?.let { " $it" } ?: ""),
                color = P.text2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun Hero(m: Match, onOpen: (Match) -> Unit) {
    Card(Modifier.clickable { onOpen(m) }) {
        Column {
            Row(Modifier.fillMaxWidth().height(4.dp)) {
                Box(Modifier.weight(1f).height(4.dp).background(Blau)); Box(Modifier.weight(1f).height(4.dp).background(Grana))
            }
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (m.isLive) LiveFlag() else SectionTitle("Next match")
                    Spacer(Modifier.weight(1f))
                    Text(Ui.compName(m), color = P.text2, fontSize = 13.sp)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamBlock(m.home, Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(128.dp)) {
                        if (m.isLive) {
                            Text(m.scoreText ?: "0-0", color = P.text, fontFamily = Display, fontSize = 44.sp)
                            Text(Ui.clock(m), color = P.live, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        } else {
                            Text(Ui.time(m), color = P.text, fontFamily = Display, fontSize = 30.sp, maxLines = 1, softWrap = false)
                            Text(Ui.countdown(m), color = P.gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    TeamBlock(m.away, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Text(Ui.longDate(m) + (m.venue?.let { "  ·  $it" } ?: ""), color = P.text2, fontSize = 13.sp, textAlign = TextAlign.Center)
                m.tv?.let { Spacer(Modifier.height(14.dp)); WatchIndia(it) }
            }
        }
    }
}

@Composable
fun LiveFlag() {
    Text("LIVE", color = P.onGold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(P.live).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun TeamBlock(t: com.fcbtracker.core.TeamRef, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(t, 56.dp)
        Spacer(Modifier.height(8.dp))
        Text(t.short, color = P.text, fontWeight = if (t.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** "Watch in India": each channel opens its own site (FanCode opens the match page when known). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WatchIndia(tv: TvListing) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        Text("Watch in India", color = P.text2, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
        tv.channels.forEach { ch ->
            val link = Tv.channelLink(ch, tv)
            Text(ch, color = P.onGold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(P.gold)
                    .let { if (link != null) it.clickable { context.openUrl(link) } else it }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun MatchLine(m: Match) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.home.short, Modifier.weight(1f), color = P.text, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (m.home.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.width(8.dp)); Crest(m.home, 30.dp)
        Text(m.scoreText ?: "-", Modifier.width(72.dp), color = P.text, fontFamily = Display, fontSize = 26.sp, textAlign = TextAlign.Center)
        Crest(m.away, 30.dp); Spacer(Modifier.width(8.dp))
        Text(m.away.short, Modifier.weight(1f), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (m.away.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Normal)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ResultBadge(m.result, 20.dp); Spacer(Modifier.width(8.dp))
        Text("${Ui.compShort(m)}  ·  ${Ui.dayLabel(m)}", color = P.text2, fontSize = 12.sp)
    }
}

@Composable
fun MatchRow(m: Match, onOpen: (Match) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(78.dp)) {
            Text(Ui.dayLabel(m).substringBefore(","), color = P.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(Ui.compShort(m), color = P.text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Crest(m.home, 22.dp); Spacer(Modifier.width(6.dp))
            Text(m.home.abbr ?: m.home.short, color = P.text, fontSize = 14.sp, fontWeight = if (m.home.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Normal)
            Text(if (m.isUpcoming) Ui.time(m) else m.scoreText ?: "-", Modifier.weight(1f), color = if (m.isLive) P.live else P.text,
                fontFamily = Display, fontSize = if (m.isUpcoming) 15.sp else 18.sp, textAlign = TextAlign.Center)
            Text(m.away.abbr ?: m.away.short, color = P.text, fontSize = 14.sp, fontWeight = if (m.away.id == FCB_ESPN_ID) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.width(6.dp)); Crest(m.away, 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            when {
                m.isLive -> Text("LIVE", color = P.live, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                m.isPlayed -> ResultBadge(m.result, 22.dp)
            }
        }
    }
    if (m.isUpcoming) m.tv?.let {
        Text("India: ${it.channels.joinToString(", ")}", color = P.gold, fontSize = 11.sp, modifier = Modifier.padding(start = 94.dp, bottom = 6.dp))
    }
}

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Matches(s: Snapshot, onOpen: (Match) -> Unit) {
    var which by rememberSaveable { mutableIntStateOf(if (s.upcoming.isNotEmpty()) 1 else 0) }
    val list = if (which == 0) s.played.reversed() else s.withTv.filter { !it.isPlayed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                listOf("Results (${s.played.size})", "Fixtures (${s.upcoming.size})").forEachIndexed { i, label ->
                    SegmentedButton(selected = which == i, onClick = { which = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
        }
        monthGroups(list, onOpen)
    }
}

private fun LazyListScope.monthGroups(list: List<Match>, onOpen: (Match) -> Unit) {
    list.groupBy { it.kickoff.atZone(ZoneId.systemDefault()).format(monthFmt) }.forEach { (month, ms) ->
        item(key = "m-$month") { SectionTitle(month, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        items(ms, key = { it.id }) { MatchRow(it, onOpen) }
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("#", Modifier.width(28.dp), color = P.text2, fontSize = 12.sp)
        Text("Team", Modifier.weight(1f), color = P.text2, fontSize = 12.sp)
        listOf("P", "GD", "Pts").forEach { Text(it, Modifier.width(40.dp), color = P.text2, fontSize = 12.sp, textAlign = TextAlign.End) }
    }
}

@Composable
private fun TableLine(r: TableRow) {
    val us = r.team.id == FCB_ESPN_ID
    Row(Modifier.fillMaxWidth().background(if (us) P.surface2 else P.surface).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${r.rank}", Modifier.width(28.dp), color = if (us) P.gold else P.text2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Crest(r.team, 22.dp); Spacer(Modifier.width(10.dp))
        Text(r.team.short, Modifier.weight(1f), color = P.text, fontWeight = if (us) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1)
        Text("${r.played}", Modifier.width(40.dp), color = P.text, fontSize = 14.sp, textAlign = TextAlign.End)
        Text(if (r.gd > 0) "+${r.gd}" else "${r.gd}", Modifier.width(40.dp), color = P.text, fontSize = 14.sp, textAlign = TextAlign.End)
        Text("${r.points}", Modifier.width(40.dp), color = P.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun FullTable(s: Snapshot) {
    val t = s.table
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (t == null) { item { Text("Table not loaded yet.", color = P.text2) }; return@LazyColumn }
        item { Card { Column(Modifier.padding(vertical = 12.dp)) {
            SectionTitle(t.league.removePrefix("Spanish "), Modifier.padding(horizontal = 16.dp)); Spacer(Modifier.height(8.dp))
            TableHeader(); t.rows.forEach { TableLine(it) }
        } } }
    }
}
