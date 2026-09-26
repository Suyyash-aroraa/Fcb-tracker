package com.fcbtracker.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ESPN's public JSON: fixtures and results in every competition, match summaries (events, team
 * stats, lineups), the squad, news and the LALIGA table.
 */
object Espn {
    private const val SITE = "https://site.web.api.espn.com/apis/site/v2/sports/soccer"
    const val STANDINGS = "https://site.web.api.espn.com/apis/v2/sports/soccer/esp.1/standings"
    const val RESULTS = "$SITE/all/teams/$FCB_ESPN_ID/schedule"
    const val FIXTURES = "$SITE/all/teams/$FCB_ESPN_ID/schedule?fixture=true"

    const val SQUAD = "$SITE/esp.1/teams/$FCB_ESPN_ID/roster"
    const val NEWS = "$SITE/esp.1/news?team=$FCB_ESPN_ID"
    fun seasonUrl(year: Int) = "$RESULTS?season=$year"

    fun summaryUrl(m: Match) = "$SITE/${m.competition.slug ?: "all"}/summary?event=${m.id}"

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val STATS = listOf(
        Triple("possessionPct", "Possession", "pct"),
        Triple("totalShots", "Shots", "count"),
        Triple("shotsOnTarget", "Shots on target", "count"),
        Triple("wonCorners", "Corners", "count"),
        Triple("totalPasses", "Passes", "count"),
        Triple("passAccuracy", "Pass accuracy", "pct"),
        Triple("totalTackles", "Tackles", "count"),
        Triple("saves", "Saves", "count"),
        Triple("foulsCommitted", "Fouls", "count"),
        Triple("offsides", "Offsides", "count"),
        Triple("yellowCards", "Yellow cards", "count"),
        Triple("redCards", "Red cards", "count"),
    )

    // ---- small JSON helpers
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
    private fun JsonElement?.int(): Int? = num()?.toInt()
    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

    private fun logo(team: JsonElement?): String? {
        val logos = team["logos"].arr()
        return logos.firstOrNull { l -> l["rel"].arr().none { it.str() == "dark" } }?.get("href").str()
            ?: logos.firstOrNull()?.get("href").str()
            ?: team["logo"].str()
    }

    private fun teamRef(c: JsonElement?): TeamRef {
        val team = c["team"]
        val score = c["score"]
        val (value, shootout) = if (score is JsonObject) score["value"].int() to score["shootoutScore"].int()
        else score.int() to c["shootoutScore"].int()
        val name = team["displayName"].str() ?: team["name"].str() ?: "?"
        return TeamRef(
            id = team["id"].str() ?: c["id"].str() ?: "",
            name = name,
            short = team["shortDisplayName"].str() ?: name,
            abbr = team["abbreviation"].str(),
            logo = logo(team),
            score = value,
            shootout = shootout,
        )
    }

    private fun status(s: JsonElement?): Status {
        val t = s["type"]
        return Status(
            state = t["state"].str() ?: "pre",
            completed = t["completed"].bool(),
            detail = t["detail"].str() ?: t["description"].str(),
            short = t["shortDetail"].str(),
            clock = s["displayClock"].str(),
        )
    }

    fun normalizeEvent(event: JsonElement): Match {
        val comp = event["competitions"].arr().firstOrNull()
        val cs = comp["competitors"].arr()
        val home = teamRef(cs.firstOrNull { it["homeAway"].str() == "home" } ?: cs.firstOrNull())
        val away = teamRef(cs.firstOrNull { it["homeAway"].str() == "away" } ?: cs.lastOrNull())
        val fcbSide = if (home.id == FCB_ESPN_ID) "home" else "away"
        val st = status(comp["status"] ?: event["status"])
        var result: String? = null
        if (st.completed && home.score != null && away.score != null) {
            val (us, them) = if (fcbSide == "home") home to away else away to home
            result = when {
                us.score!! > them.score!! -> "W"
                us.score < them.score -> "L"
                us.shootout != null && them.shootout != null -> if (us.shootout > them.shootout) "W" else "L"
                else -> "D"
            }
        }
        val league = event["league"]
        return Match(
            id = event["id"].str() ?: "",
            date = comp["date"].str() ?: event["date"].str() ?: "",
            competition = Competition(league["id"].str() ?: "", league["name"].str() ?: event["seasonType"]["name"].str() ?: "", league["slug"].str()),
            note = comp["notes"].arr().firstNotNullOfOrNull { it["headline"].str() },
            venue = comp["venue"]["fullName"].str(),
            status = st,
            home = home,
            away = away,
            fcbSide = fcbSide,
            result = result,
        )
    }

    /** Results and fixtures in every competition, de-duplicated and sorted by kick-off. */
    fun parseMatches(results: String, fixtures: String): Pair<List<Match>, String?> {
        val r = json.parseToJsonElement(results)
        val f = json.parseToJsonElement(fixtures)
        val byId = LinkedHashMap<String, Match>()
        for (payload in listOf(r, f)) for (e in payload["events"].arr()) normalizeEvent(e).let { byId[it.id] = it }
        return byId.values.sortedBy { it.date } to r["season"]["displayName"].str()
    }

    private fun eventKind(ev: JsonElement): String? {
        val type = ev["type"]["type"].str() ?: ""
        if (ev["shootout"].bool()) return null
        if (type.startsWith("own-goal") || (ev["scoringPlay"].bool() && (ev["text"].str() ?: "").lowercase().contains("own goal"))) return "own-goal"
        if (ev["scoringPlay"].bool() || type.startsWith("goal")) return "goal"
        return when {
            type == "penalty---missed" || type == "penalty---saved" -> "pen-miss"
            type == "yellow-card" -> "yellow"
            "red-card" in type -> "red"
            type == "substitution" -> "sub"
            type == "halftime" || type == "end-regular-time" -> "period"
            else -> null
        }
    }

    fun parseDetail(summary: String, base: Match): MatchDetail {
        val s = json.parseToJsonElement(summary)
        var match = base
        val header = s["header"]["competitions"].arr().firstOrNull()
        if (header["competitors"].arr().isNotEmpty()) {
            // The header carries fresher score and clock than the schedule during live games.
            val live = normalizeEvent(JsonObject(mapOf("id" to JsonPrimitive(base.id), "competitions" to JsonArray(listOf(header!!)))))
            match = match.copy(status = live.status, home = live.home.copy(logo = live.home.logo ?: base.home.logo),
                away = live.away.copy(logo = live.away.logo ?: base.away.logo), result = live.result ?: base.result)
        }
        val homeId = match.home.id
        val events = s["keyEvents"].arr().mapNotNull { ev ->
            val kind = eventKind(ev) ?: return@mapNotNull null
            val label = ev["type"]["text"].str() ?: ""
            val teamId = ev["team"]["id"].str()
            MatchEvent(
                kind = kind,
                label = label,
                minute = ev["clock"]["displayValue"].str() ?: "",
                side = teamId?.let { if (it == homeId) "home" else "away" },
                players = ev["participants"].arr().mapNotNull { it["athlete"]["displayName"].str() },
                penalty = kind == "goal" && label.lowercase().contains("penalty"),
            )
        }
        val teams = s["boxscore"]["teams"].arr()
        val raw = HashMap<String, MutableMap<String, Double?>>()
        for (t in teams) {
            val side = if (t["team"]["id"].str() == homeId) "home" else "away"
            raw[side] = t["statistics"].arr().associate { (it["name"].str() ?: "") to it["displayValue"].num() }.toMutableMap()
        }
        val stats = if (raw.keys == setOf("home", "away")) {
            raw.values.forEach { side ->
                val acc = side["accuratePasses"]; val tot = side["totalPasses"]
                side["passAccuracy"] = if (acc != null && tot != null && tot > 0) Math.round(1000 * acc / tot) / 10.0 else null
            }
            STATS.mapNotNull { (key, label, type) ->
                val h = raw["home"]!![key]; val a = raw["away"]!![key]
                if (h == null && a == null) null else TeamStat(label, h, a, type)
            }
        } else emptyList()
        val lineups = s["rosters"].arr().associate { roster ->
            val side = if (roster["team"]["id"].str() == homeId) "home" else "away"
            val players = roster["roster"].arr().map { p ->
                LineupPlayer(
                    name = p["athlete"]["displayName"].str() ?: "?",
                    number = p["jersey"].str(),
                    pos = p["position"]["abbreviation"].str(),
                    starter = p["starter"].bool(),
                    subbedIn = p["subbedIn"].bool(),
                    subbedOut = p["subbedOut"].bool(),
                    subMinute = p["plays"].arr().lastOrNull { it["substitution"].bool() }?.get("clock")?.get("displayValue").str(),
                    short = p["athlete"]["shortName"].str(),
                    place = p["formationPlace"].int() ?: 0,
                )
            }
            side to Lineup(
                formation = roster["formation"].str(),
                starters = players.filter { it.starter }.sortedBy { it.place },
                subs = players.filter { !it.starter },
            )
        }.filterValues { it.starters.isNotEmpty() }
        val info = s["gameInfo"]
        return MatchDetail(
            match = match,
            events = events,
            stats = stats,
            lineups = lineups,
            venue = info["venue"]["fullName"].str() ?: match.venue,
            attendance = info["attendance"].int()?.takeIf { it > 0 },
            referee = info["officials"].arr().firstOrNull()?.get("displayName").str(),
        )
    }

    fun parseStandings(data: String): Table {
        val d = json.parseToJsonElement(data)
        val group = d["children"].arr().firstOrNull()
        val rows = group["standings"]["entries"].arr().map { e ->
            val stats = e["stats"].arr().associateBy { it["name"].str() ?: "" }
            fun v(n: String) = stats[n]?.get("value").int() ?: 0
            val team = e["team"]
            val name = team["displayName"].str() ?: "?"
            TableRow(
                rank = v("rank"),
                team = TeamRef(team["id"].str() ?: "", name, team["shortDisplayName"].str() ?: name, team["abbreviation"].str(), logo(team)),
                played = v("gamesPlayed"), won = v("wins"), drawn = v("ties"), lost = v("losses"),
                gd = v("pointDifferential"), points = v("points"),
            )
        }.sortedBy { if (it.rank > 0) it.rank else 99 }
        return Table(group["name"].str() ?: d["name"].str() ?: "LALIGA", rows)
    }

    fun parseSquad(data: String): List<Player> = json.parseToJsonElement(data)["athletes"].arr().map { a ->
        val stats = HashMap<String, Int?>()
        for (cat in a["statistics"]["splits"]["categories"].arr()) for (st in cat["stats"].arr()) stats[st["name"].str() ?: ""] = st["value"].int()
        Player(
            id = a["id"].str() ?: "",
            name = a["displayName"].str() ?: "?",
            short = a["shortName"].str(),
            number = a["jersey"].str(),
            pos = a["position"]["abbreviation"].str(),
            posName = a["position"]["displayName"].str(),
            age = a["age"].int(),
            nationality = a["citizenship"].str(),
            flag = a["flag"]["href"].str(),
            headshot = a["headshot"]["href"].str(),
            injured = a["injuries"].arr().isNotEmpty(),
            stats = PlayerStats(
                apps = stats["appearances"], subIns = stats["subIns"], goals = stats["totalGoals"], assists = stats["goalAssists"],
                shots = stats["totalShots"], shotsOnTarget = stats["shotsOnTarget"], yellow = stats["yellowCards"], red = stats["redCards"],
                fouls = stats["foulsCommitted"], saves = stats["saves"], conceded = stats["goalsConceded"],
            ),
        )
    }

    fun parseNews(data: String): List<NewsItem> = json.parseToJsonElement(data)["articles"].arr().mapNotNull { a ->
        val link = a["links"]["web"]["href"].str() ?: return@mapNotNull null
        NewsItem(
            title = a["headline"].str() ?: return@mapNotNull null,
            summary = a["description"].str(),
            url = link,
            image = a["images"].arr().firstNotNullOfOrNull { it["url"].str() },
            source = "ESPN",
            published = a["published"].str(),
            origin = "espn",
        )
    }
}
