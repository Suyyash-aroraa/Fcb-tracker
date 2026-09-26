package com.fcbtracker.core

import kotlinx.serialization.Serializable
import java.time.Instant

const val FCB_ESPN_ID = "83"

@Serializable
data class TeamRef(
    val id: String,
    val name: String,
    val short: String,
    val abbr: String? = null,
    val logo: String? = null,
    val score: Int? = null,
    val shootout: Int? = null,
)

@Serializable
data class Status(
    /** "pre", "in" or "post", as ESPN names them. */
    val state: String,
    val completed: Boolean = false,
    val detail: String? = null,
    val short: String? = null,
    val clock: String? = null,
)

@Serializable
data class Competition(val id: String = "", val name: String = "", val slug: String? = null)

@Serializable
data class TvListing(
    val channels: List<String>,
    val url: String,
    /** "LiveSoccerTV" or "FanCode". */
    val source: String,
)

@Serializable
data class Match(
    val id: String,
    val date: String,
    val competition: Competition,
    val note: String? = null,
    val venue: String? = null,
    val status: Status,
    val home: TeamRef,
    val away: TeamRef,
    /** "home" or "away": which side Barcelona is. */
    val fcbSide: String,
    /** "W", "D" or "L" for Barcelona, once completed. */
    val result: String? = null,
    val tv: TvListing? = null,
) {
    val fcb: TeamRef get() = if (fcbSide == "home") home else away
    val opponent: TeamRef get() = if (fcbSide == "home") away else home
    val kickoff: Instant get() = parseInstant(date) ?: Instant.EPOCH
    val isLive: Boolean get() = status.state == "in"
    val isPlayed: Boolean get() = status.state == "post"
    val isUpcoming: Boolean get() = status.state == "pre"
    val scoreText: String? get() = if (home.score != null && away.score != null) "${home.score}-${away.score}" else null
}

@Serializable
data class MatchEvent(
    /** goal, own-goal, pen-miss, yellow, red, sub or period. */
    val kind: String,
    val label: String,
    val minute: String,
    /** The side credited: for own goals, the team that benefits. */
    val side: String? = null,
    val players: List<String> = emptyList(),
    val penalty: Boolean = false,
)

@Serializable
data class TeamStat(val label: String, val home: Double?, val away: Double?, val type: String)

@Serializable
data class LineupPlayer(
    val name: String,
    val number: String? = null,
    val pos: String? = null,
    val starter: Boolean,
    val subbedIn: Boolean = false,
    val subbedOut: Boolean = false,
    val subMinute: String? = null,
    val short: String? = null,
    /** ESPN's formation slot (1 = goalkeeper). */
    val place: Int = 0,
)

@Serializable
data class Lineup(val formation: String? = null, val starters: List<LineupPlayer>, val subs: List<LineupPlayer>)

@Serializable
data class MatchDetail(
    val match: Match,
    val events: List<MatchEvent>,
    val stats: List<TeamStat>,
    val lineups: Map<String, Lineup> = emptyMap(),
    val venue: String? = null,
    val attendance: Int? = null,
    val referee: String? = null,
)

@Serializable
data class TableRow(
    val rank: Int,
    val team: TeamRef,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val gd: Int,
    val points: Int,
)

@Serializable
data class Table(val league: String, val rows: List<TableRow>)

@Serializable
data class PlayerStats(
    val apps: Int? = null, val subIns: Int? = null, val goals: Int? = null, val assists: Int? = null,
    val shots: Int? = null, val shotsOnTarget: Int? = null, val yellow: Int? = null, val red: Int? = null,
    val fouls: Int? = null, val saves: Int? = null, val conceded: Int? = null,
)

@Serializable
data class Player(
    val id: String,
    val name: String,
    val short: String? = null,
    val number: String? = null,
    /** G, D, M or F. */
    val pos: String? = null,
    val posName: String? = null,
    val age: Int? = null,
    val nationality: String? = null,
    val flag: String? = null,
    /** Official club photo when matched, else ESPN's headshot. */
    val headshot: String? = null,
    val profile: String? = null,
    val injured: Boolean = false,
    val stats: PlayerStats = PlayerStats(),
)

@Serializable
data class NewsItem(
    val title: String,
    val summary: String? = null,
    val url: String,
    val image: String? = null,
    val source: String? = null,
    val published: String? = null,
    /** official, google or espn. */
    val origin: String,
)

/** Everything the app and widget show, as last fetched. Saved on the phone between refreshes. */
@Serializable
data class Snapshot(
    val updatedAt: String,
    val season: String? = null,
    val matches: List<Match> = emptyList(),
    val table: Table? = null,
    /** India TV listings by match id, and when they were last checked. */
    val tv: Map<String, TvListing> = emptyMap(),
    val tvCheckedAt: String? = null,
    val squad: List<Player> = emptyList(),
    val squadCheckedAt: String? = null,
    val news: List<NewsItem> = emptyList(),
    val newsCheckedAt: String? = null,
    /** Per-source problems from the last refresh, for the status line. */
    val errors: Map<String, String> = emptyMap(),
) {
    val withTv: List<Match> get() = matches.map { m -> tv[m.id]?.let { m.copy(tv = it) } ?: m }
    val live: Match? get() = withTv.firstOrNull { it.isLive }
    val upcoming: List<Match> get() = withTv.filter { it.isUpcoming }
    val played: List<Match> get() = withTv.filter { it.isPlayed }
    val next: Match? get() = upcoming.firstOrNull()
    val last: Match? get() = played.lastOrNull()
    val form: List<Match> get() = played.takeLast(5).reversed()
    val position: TableRow? get() = table?.rows?.firstOrNull { it.team.id == FCB_ESPN_ID }
}

fun parseInstant(s: String?): Instant? {
    if (s.isNullOrBlank()) return null
    return runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { Instant.parse(s.replace("Z", ":00Z")) }.getOrNull() // ESPN: 2026-10-10T16:30Z
        ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
}

/** Rank a position code by role: goalkeeper, defence, defensive midfield, midfield, attacking midfield, attack. */
private fun roleRank(pos: String?): Int {
    val p = (pos ?: "").uppercase()
    return when {
        p == "G" || p == "GK" -> 0
        Regex("^(CD|SW|RB|LB|D|CB|RWB|LWB)").containsMatchIn(p) -> 1
        p.startsWith("DM") -> 2
        Regex("^(CM|LM|RM|M)(-|$)").containsMatchIn(p) -> 3
        p.startsWith("AM") -> 4
        else -> 5
    }
}

private fun lateral(pos: String?): Int {
    val p = pos ?: ""
    return if (Regex("(^L|-L$)").containsMatchIn(p)) 0 else if (Regex("(^R|-R$)").containsMatchIn(p)) 2 else 1
}

/**
 * The starters in lines from goalkeeper to attack, each line left to right from the team's own
 * point of view: players ranked by role, then chunked by the formation string (same as the web app).
 */
fun Lineup.lines(): List<List<LineupPlayer>> {
    val gk = starters.filter { roleRank(it.pos) == 0 }
    val out = starters.filter { roleRank(it.pos) != 0 }.sortedWith(compareBy({ roleRank(it.pos) }, { it.place })).toMutableList()
    val counts = (formation ?: "").split("-").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
    val shape = if (counts.sum() == out.size) counts else listOf(out.size)
    val lines = mutableListOf(if (gk.isNotEmpty()) gk else listOfNotNull(out.removeFirstOrNull()))
    var i = 0
    for (c in shape) { lines += out.subList(minOf(i, out.size), minOf(i + c, out.size)).toList(); i += c }
    return lines.filter { it.isNotEmpty() }.map { l -> l.sortedWith(compareBy({ lateral(it.pos) }, { it.place })) }
}
