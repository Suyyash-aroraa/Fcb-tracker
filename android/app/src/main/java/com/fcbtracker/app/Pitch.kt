package com.fcbtracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fcbtracker.R
import com.fcbtracker.core.Lineup
import com.fcbtracker.core.LineupPlayer
import com.fcbtracker.core.MatchEvent
import com.fcbtracker.core.TeamRef
import com.fcbtracker.core.lines


/** One team's lineup on half a pitch: attack at the top, goalkeeper at the bottom, then the bench. */
@Composable
fun LineupPitch(team: TeamRef, lu: Lineup, events: List<MatchEvent>, isFcb: Boolean) {
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(team, 26.dp); Spacer(Modifier.width(10.dp))
                Text(team.short.uppercase(), Modifier.weight(1f), style = T.display.copy(fontSize = 20.sp, letterSpacing = 0.6.sp), color = P.text)
                lu.formation?.let {
                    Text(it, style = T.num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = P.accentText,
                        modifier = Modifier.clip(PillShape).border(1.dp, P.line, PillShape).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(0.82f).clip(RoundedCornerShape(12.dp))) {
                PitchMarkings()
                Column(Modifier.fillMaxSize().padding(vertical = 10.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    lu.lines().reversed().forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            line.forEach { PlayerDot(it, events, isFcb, Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (lu.subs.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("BENCH", style = T.small.copy(fontSize = 11.sp, letterSpacing = 0.9.sp, fontWeight = FontWeight.SemiBold), color = P.text3)
                Spacer(Modifier.height(4.dp))
                lu.subs.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.number ?: "", Modifier.width(30.dp), style = T.num.copy(fontSize = 13.sp), color = P.text3)
                        Text(p.name, Modifier.weight(1f), style = T.label.copy(fontSize = 14.sp), color = if (p.subbedIn) P.text else P.text2)
                        if (p.subbedIn) {
                            Icon(R.drawable.ic_arrow_up, size = 14.dp, tint = P.win)
                            Text(p.subMinute ?: "", style = T.num.copy(fontSize = 12.sp), color = P.text2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PitchMarkings() {
    val turf = P.pitch; val chalk = P.pitchLine
    val turf2 = turf.copy(red = turf.red * 0.9f, green = turf.green * 0.9f, blue = turf.blue * 0.9f)
    Canvas(Modifier.fillMaxSize()) {
        val stripes = 8
        val h = size.height / stripes
        for (i in 0 until stripes) drawRect(if (i % 2 == 0) turf else turf2, Offset(0f, i * h), Size(size.width, h))
        val stroke = Stroke(width = 1.5.dp.toPx())
        val m = 8.dp.toPx()
        // Touchlines, with the halfway line at the top edge
        drawRect(chalk, Offset(m, m), Size(size.width - 2 * m, size.height - 2 * m), style = stroke)
        // Centre circle, half of it on this half
        val r = size.width * 0.14f
        drawArc(chalk, 0f, 180f, false, Offset(size.width / 2 - r, m - r), Size(2 * r, 2 * r), style = stroke)
        // Penalty area, goal area and the D at the bottom
        val boxW = size.width * 0.58f; val boxH = size.height * 0.17f
        drawRect(chalk, Offset((size.width - boxW) / 2, size.height - m - boxH), Size(boxW, boxH), style = stroke)
        val sixW = size.width * 0.28f; val sixH = size.height * 0.065f
        drawRect(chalk, Offset((size.width - sixW) / 2, size.height - m - sixH), Size(sixW, sixH), style = stroke)
        val d = size.width * 0.11f
        drawArc(chalk, 180f, 180f, false, Offset(size.width / 2 - d, size.height - m - boxH - d * 0.6f), Size(2 * d, 1.2f * d), style = stroke)
    }
}

private fun surname(name: String): String {
    val parts = name.trim().split(" ")
    return if (parts.size <= 1) name else parts.drop(1).joinToString(" ").let { if (it.length > 13) parts.last() else it }
}

@Composable
private fun PlayerDot(p: LineupPlayer, events: List<MatchEvent>, isFcb: Boolean, modifier: Modifier) {
    val goals = events.count { it.kind == "goal" && it.players.firstOrNull() == p.name }
    val card = events.lastOrNull { (it.kind == "yellow" || it.kind == "red") && it.players.firstOrNull() == p.name }
    val gk = p.pos == "G" || p.pos == "GK"
    val label = listOfNotNull(p.number?.let { "Number $it" }, p.name, goals.takeIf { it > 0 }?.let { "$it goal${if (it > 1) "s" else ""}" },
        card?.let { if (it.kind == "red") "sent off" else "booked" }, p.subMinute?.takeIf { p.subbedOut }?.let { "subbed off $it" }).joinToString(", ")
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 60.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            val fill = when {
                gk -> Brush.linearGradient(listOf(P.accent, P.accent))
                isFcb -> Brush.verticalGradient(listOf(Blau, Grana))
                else -> Brush.linearGradient(listOf(Color(0xFFF2F4F8), Color(0xFFD9DEE8)))
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(fill).border(2.dp, Color(0xCCFFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Text(p.number ?: "", color = if (isFcb && !gk) Color.White else Color(0xFF0B1020), style = T.display.copy(fontSize = 16.sp))
            }
            if (goals > 0) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 0.dp, y = (-1).dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(R.drawable.ic_soccer_ball_fill, size = 10.dp, tint = Color(0xFF0B1020))
                        if (goals > 1) Text("$goals", style = T.num.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF0B1020))
                    }
                }
            }
            card?.let {
                Box(Modifier.align(Alignment.TopStart).offset(x = 6.dp, y = 1.dp).size(8.dp, 11.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (it.kind == "red") Color(0xFFE5324A) else Color(0xFFF2C94C)))
            }
        }
        Text(surname(p.short ?: p.name).let { if (p.short != null) p.short.substringAfter(". ") else it }, color = Color.White, style = T.label.copy(fontSize = 11.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp))
        if (p.subbedOut) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(R.drawable.ic_arrow_down, size = 10.dp, tint = Color(0xFFFFB3BE))
            Text(p.subMinute ?: "off", style = T.num.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFFFFB3BE))
        }
    }
}
