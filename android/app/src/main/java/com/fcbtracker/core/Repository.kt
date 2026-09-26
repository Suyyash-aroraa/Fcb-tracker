package com.fcbtracker.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Fetches everything straight from the sources and keeps the latest [Snapshot] in [dir], so the
 * widget and the app show the last data offline. No server in between.
 */
class Repository(private val dir: File, private val http: Http = Http(), private val clock: () -> Instant = Instant::now) {
    private val file = File(dir, "snapshot.json")
    private val mutex = Mutex()

    companion object {
        val LIVE_BEFORE: Duration = Duration.ofMinutes(75) // lineups appear about an hour before
        val LIVE_AFTER: Duration = Duration.ofMinutes(210) // extra time and penalties run long
        val TV_EVERY: Duration = Duration.ofHours(6)
        const val TV_UPCOMING = 6
    }

    fun load(): Snapshot? = runCatching { Espn.json.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrNull()

    private fun save(s: Snapshot) {
        dir.mkdirs()
        val tmp = File(dir, "snapshot.json.tmp")
        tmp.writeText(Espn.json.encodeToString(Snapshot.serializer(), s))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Fixtures in the live window: from 75 minutes before kick-off until the match ends. */
    fun inLiveWindow(s: Snapshot?, now: Instant = clock()): List<Match> = s?.matches.orEmpty().filter {
        !it.status.completed && now >= it.kickoff - LIVE_BEFORE && now <= it.kickoff + LIVE_AFTER
    }

    /**
     * Full refresh: schedule, table and (every 6 hours) India TV listings, then the live summary of
     * any match in its live window. A source that fails keeps its previous data.
     */
    suspend fun refresh(forceTv: Boolean = false): Snapshot = mutex.withLock {
        val old = load()
        val errors = LinkedHashMap<String, String>()
        var s = old ?: Snapshot(updatedAt = clock().toString())
        coroutineScope {
            val results = async { runCatching { http.text(Espn.RESULTS) } }
            val fixtures = async { runCatching { http.text(Espn.FIXTURES) } }
            val table = async { runCatching { Espn.parseStandings(http.text(Espn.STANDINGS)) } }
            runCatching {
                val (matches, season) = Espn.parseMatches(results.await().getOrThrow(), fixtures.await().getOrThrow())
                // Keep live state the schedule list hasn't caught up with yet.
                val prev = old?.matches.orEmpty().associateBy { it.id }
                s = s.copy(matches = matches.map { m -> prev[m.id]?.takeIf { it.isLive && m.isUpcoming } ?: m }, season = season ?: s.season)
            }.onFailure { errors["espn"] = it.message ?: "failed" }
            table.await().onSuccess { s = s.copy(table = it) }.onFailure { errors["table"] = it.message ?: "failed" }
        }
        val tvDue = forceTv || s.tvCheckedAt == null ||
            Duration.between(parseInstant(s.tvCheckedAt) ?: Instant.EPOCH, clock()) > TV_EVERY ||
            s.upcoming.take(3).any { it.id !in s.tv }
        if (tvDue) s = tv(s, errors)
        s = live(s)
        s = s.copy(updatedAt = clock().toString(), errors = errors + s.errors.filterKeys { it == "live" && it !in errors })
        save(s)
        s
    }

    /** Live-only refresh: one summary per match in its live window. Cheap enough to run every minute. */
    suspend fun refreshLive(): Snapshot? = mutex.withLock {
        val s = load() ?: return@withLock null
        if (inLiveWindow(s).isEmpty()) return@withLock s
        live(s).also { save(it.copy(updatedAt = clock().toString())) }
    }

    private suspend fun live(s: Snapshot): Snapshot {
        var out = s
        for (m in inLiveWindow(s)) {
            runCatching { Espn.parseDetail(http.text(Espn.summaryUrl(m)), m) }
                .onSuccess { d ->
                    out = out.copy(matches = out.matches.map { if (it.id == m.id) d.match.copy(tv = null) else it }, errors = out.errors - "live")
                    saveDetail(d)
                }
                .onFailure { out = out.copy(errors = out.errors + ("live" to (it.message ?: "failed"))) }
        }
        return out
    }

    private suspend fun tv(s: Snapshot, errors: MutableMap<String, String>): Snapshot {
        val wanted = (s.matches.filter { it.isLive } + s.matches.filter { it.isUpcoming }.take(TV_UPCOMING))
        val ids = s.matches.map { it.id }.toSet()
        val guide = s.tv.filterKeys { it in ids }.toMutableMap()
        val listings = runCatching { Tv.parseTeam(http.text(Tv.TEAM_URL)) }
            .onFailure { errors["livesoccertv"] = it.message ?: "failed" }.getOrDefault(emptyList())
        var tours: Map<String, List<String>> = emptyMap()
        val fancode = mutableListOf<Tv.FanCodeItem>()
        runCatching {
            tours = Tv.parseFanCodeTours(http.text(Tv.FANCODE_FOOTBALL))
            val seen = HashSet<String>()
            for (comp in wanted.mapNotNull { it.competition.slug }.toSet() intersect tours.keys) for (url in tours.getValue(comp)) {
                runCatching { Tv.parseFanCode(http.text(url), comp, url) }.getOrDefault(emptyList())
                    .forEach { if (seen.add(it.slug)) fancode += it }
            }
        }.onFailure { errors["fancode"] = it.message ?: "failed" }
        for (m in wanted) {
            var entry: TvListing? = null
            Tv.matchListing(m, listings)?.let { l ->
                runCatching { Tv.parseCountry(http.text(l.url)) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { entry = TvListing(it, l.url, "LiveSoccerTV") }
            }
            if (entry == null) Tv.matchFanCode(m, fancode)?.let { entry = TvListing(listOf("FanCode"), it.url, "FanCode") }
            val comp = m.competition.slug
            if (entry == null && comp != null && comp in tours && m.id !in guide) {
                // FanCode carries the competition but hasn't listed the fixture yet: link its schedule.
                entry = TvListing(listOf("FanCode"), tours.getValue(comp).first(), "FanCode")
            }
            entry?.let { guide[m.id] = it }
        }
        if (listings.isEmpty() && fancode.isEmpty() && tours.isEmpty()) return s // keep the last good listings
        return s.copy(tv = guide, tvCheckedAt = clock().toString())
    }

    // ---- match details, fetched when a match is opened; finished matches are kept on the phone

    private fun detailFile(id: String) = File(dir, "details/$id.json")

    private fun saveDetail(d: MatchDetail) {
        runCatching {
            detailFile(d.match.id).apply { parentFile?.mkdirs() }.writeText(Espn.json.encodeToString(MatchDetail.serializer(), d))
        }
    }

    fun cachedDetail(id: String): MatchDetail? =
        runCatching { Espn.json.decodeFromString(MatchDetail.serializer(), detailFile(id).readText()) }.getOrNull()

    suspend fun detail(m: Match): MatchDetail {
        cachedDetail(m.id)?.takeIf { it.match.status.completed }?.let { return it.copy(match = it.match.copy(tv = m.tv)) }
        val d = Espn.parseDetail(http.text(Espn.summaryUrl(m)), m)
        saveDetail(d)
        return d.copy(match = d.match.copy(tv = m.tv))
    }

    // ---- crests, cached as files so the widget can draw them offline

    suspend fun crest(team: TeamRef): File? = withContext(Dispatchers.IO) {
        val url = team.logo ?: return@withContext null
        val f = File(dir, "crests/${team.id}.png")
        if (f.length() > 0) return@withContext f
        runCatching {
            // ESPN's image CDN resizes on request.
            val small = if ("a.espncdn.com/i/" in url) "https://a.espncdn.com/combiner/i?img=${url.substringAfter("a.espncdn.com").substringBefore("?")}&w=96&h=96" else url
            f.parentFile?.mkdirs()
            f.writeBytes(runCatching { http.bytes(small) }.getOrElse { http.bytes(url) })
            f
        }.getOrNull()
    }
}
