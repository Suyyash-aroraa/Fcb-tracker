package com.fcbtracker.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.R
import com.fcbtracker.core.FCB_ESPN_ID
import com.fcbtracker.core.Match
import com.fcbtracker.core.MatchDetail
import com.fcbtracker.core.TableRow
import com.fcbtracker.core.TeamRef
import com.fcbtracker.core.Tv
import com.fcbtracker.core.TvListing

/** A pull-to-refresh list with the app's gutters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(refreshing: Boolean, onRefresh: () -> Unit, state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp), content = content)
    }
}

/** A composed empty or error state: an icon, what happened, and what to do. */
@Composable
fun StateNote(icon: Int, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, size = 32.dp, tint = P.text3)
        Spacer(Modifier.height(10.dp))
        Text(text, style = T.body, color = P.text2, textAlign = TextAlign.Center)
    }
}

/** A text link to another view or the web, in the accent text colour. */
@Composable
fun LinkText(text: String, onClick: () -> Unit, icon: Int = R.drawable.ic_arrow_right) {
    Row(Modifier.pressable(onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.accentText)
        Spacer(Modifier.width(6.dp))
        Icon(icon, size = 16.dp, tint = P.accentText)
    }
}

fun Match.fcbWon() = result == "W"

/** Scores: the winner at full contrast, the loser at secondary contrast. */
@Composable
fun ScoreText(m: Match, fontSize: Int, modifier: Modifier = Modifier) {
    val h = m.home.score; val a = m.away.score
    val homeWins = h != null && a != null && (h > a || (h == a && (m.home.shootout ?: 0) > (m.away.shootout ?: 0)))
    val awayWins = h != null && a != null && (a > h || (h == a && (m.away.shootout ?: 0) > (m.home.shootout ?: 0)))
    val dim = P.text3
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (awayWins) dim else P.text)) { append("${h ?: "-"}") }
            withStyle(SpanStyle(color = dim, fontWeight = FontWeight.Bold)) { append("-") }
            withStyle(SpanStyle(color = if (homeWins) dim else P.text)) { append("${a ?: "-"}") }
        },
        style = T.display.copy(fontSize = fontSize.sp, letterSpacing = 1.sp), modifier = modifier.semanticsLabel("${m.home.name} ${h ?: 0}, ${m.away.name} ${a ?: 0}"),
    )
}

@Composable
fun MatchRow(m: Match, onOpen: (Match) -> Unit, showTv: Boolean = true) {
    val label = "${m.home.name} ${if (m.isUpcoming) "versus" else m.scoreText} ${m.away.name}, ${Ui.compName(m)}, ${Ui.dayLabel(m)}"
    Column(Modifier.fillMaxWidth().pressable({ onOpen(m) }).semanticsLabel(label).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(76.dp)) {
                Text(Ui.dayLabel(m).substringBefore(","), style = T.label.copy(fontWeight = FontWeight.SemiBold), color = P.text, maxLines = 1)
                Text(Ui.compShort(m), style = T.small, color = P.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Crests in home/away order with the score (or kick-off) between them.
            Crest(m.home, 26.dp)
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                if (m.isUpcoming) Text(Ui.time(m), style = T.num.copy(fontSize = 13.sp), color = P.text2, maxLines = 1)
                else ScoreText(m, 22)
            }
            Crest(m.away, 26.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.opponent.short, style = T.label.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (m.fcbSide == "home") "Home" else "Away", style = T.small, color = P.text3)
            }
            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    m.isLive -> Box(Modifier.size(8.dp).background(P.loss, PillShape))
                    m.isPlayed -> WdlChip(m.result, 22.dp)
                }
            }
        }
        if (showTv && !m.isPlayed) m.tv?.let {
            Row(Modifier.padding(start = 76.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_television_simple, size = 14.dp, tint = P.text3)
                Spacer(Modifier.width(6.dp))
                Text(it.channels.joinToString(", "), style = T.small, color = P.text2)
            }
        }
        m.note?.let { Text(it, style = T.small, color = P.text3, modifier = Modifier.padding(start = 76.dp, top = 4.dp)) }
    }
}

@Composable
private fun TeamName(t: TeamRef, modifier: Modifier, align: TextAlign) {
    val us = t.id == FCB_ESPN_ID
    Text(t.short, modifier, style = T.label.copy(fontSize = 14.sp, fontWeight = if (us) FontWeight.Bold else FontWeight.Medium),
        color = P.text, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "Watch in India": every channel opens its own site (FanCode opens the match page when known). */
@Composable
fun WatchIndia(tv: TvListing, onHero: Boolean = false) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(R.drawable.ic_television_simple, size = 16.dp, tint = P.text3)
        Spacer(Modifier.width(8.dp))
        Text("Watch in India", style = T.label, color = P.text2)
        Spacer(Modifier.width(10.dp))
        tv.channels.forEachIndexed { i, ch ->
            if (i > 0) Text(",  ", style = T.label, color = P.text3)
            val link = Tv.channelLink(ch, tv)
            Text(ch, style = T.label.copy(fontWeight = FontWeight.SemiBold, textDecoration = if (link != null) TextDecoration.Underline else null),
                color = P.accentText, modifier = if (link != null) Modifier.pressable({ context.openUrl(link) }, "Open $ch").padding(vertical = 8.dp) else Modifier)
        }
    }
}

/** Barcelona's goals in a match detail, for scorer lists. */
fun MatchDetail.goalsFor(side: String) = events.filter { (it.kind == "goal" || it.kind == "own-goal") && it.side == side }

@Composable
fun StandingsHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#", Modifier.width(26.dp), style = T.small, color = P.text3)
        Text("Team", Modifier.weight(1f), style = T.small, color = P.text3)
        listOf("P", "W", "D", "L", "GD", "Pts").forEach { h ->
            Text(h, Modifier.width(if (h == "Pts" || h == "GD") 38.dp else 28.dp), style = T.small, color = P.text3, textAlign = TextAlign.End)
        }
    }
    Hairline()
}

/** A standings row; Barcelona gets a tinted background and a gold side bar, not colour alone (bold name too). */
@Composable
fun StandingsLine(r: TableRow, compact: Boolean = false) {
    val us = r.team.id == FCB_ESPN_ID
    Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 48.dp).background(if (us) P.surface2 else P.surface), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (us) P.accent else P.surface))
        Row(Modifier.padding(start = 13.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.rank}", Modifier.width(26.dp), style = T.num.copy(fontSize = 13.sp), color = if (us) P.accentText else P.text2)
            Crest(r.team, 22.dp); Spacer(Modifier.width(10.dp))
            Text(r.team.short, Modifier.weight(1f), style = T.label.copy(fontSize = 14.sp, fontWeight = if (us) FontWeight.Bold else FontWeight.Medium),
                color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOf(r.played, r.won, r.drawn, r.lost).forEach { Text("$it", Modifier.width(28.dp), style = T.num.copy(fontSize = 13.sp), color = P.text2, textAlign = TextAlign.End) }
            Text(if (r.gd > 0) "+${r.gd}" else "${r.gd}", Modifier.width(38.dp), style = T.num.copy(fontSize = 13.sp), color = P.text2, textAlign = TextAlign.End)
            Text("${r.points}", Modifier.width(38.dp), style = T.num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = P.text, textAlign = TextAlign.End)
        }
    }
}

/** Page heading used by the list views: display title and a one-line summary. */
@Composable
fun PageHead(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
        Text(title.uppercase(), style = T.display.copy(fontSize = 34.sp, letterSpacing = 0.4.sp), color = P.text)
        sub?.let { Text(it, style = T.body.copy(fontSize = 14.sp), color = P.text2) }
    }
}

@Composable
fun SkeletonList(rows: Int = 6) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) { Skeleton(Modifier.fillMaxWidth().height(56.dp)) }
    }
}
