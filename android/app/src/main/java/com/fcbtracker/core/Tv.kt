package com.fcbtracker.core

import org.jsoup.Jsoup
import java.time.Instant
import kotlin.math.abs

/**
 * Where to watch in India. LiveSoccerTV lists broadcasters per country for every competition;
 * FanCode (LALIGA, and the Copa del Rey / Supercopa when it carries them) is the fallback.
 * Same logic as the web Worker's fcb/tv.py.
 */
object Tv {
    private const val LST = "https://www.livesoccertv.com"
    const val TEAM_URL = "$LST/teams/spain/barcelona"
    const val COUNTRY = "India"
    private const val WINDOW_S = 2 * 3600L

    const val FANCODE = "https://www.fancode.com"
    const val FANCODE_FOOTBALL = "$FANCODE/football"
    private val FANCODE_TOURS = mapOf(
        "esp.1" to Regex("^laliga-\\d"),
        "esp.copa_del_rey" to Regex("copa-del-rey"),
        "esp.super_cup" to Regex("super-?copa|super-cup"),
    )

    /** Where each broadcaster's name should take you. */
    val CHANNEL_SITES = mapOf(
        "fancode" to "https://www.fancode.com/football",
        "sony liv" to "https://www.sonyliv.com/",
        "sonyliv" to "https://www.sonyliv.com/",
        "jiotv" to "https://www.jio.com/apps/jiotv/",
        "jiocinema" to "https://www.jiocinema.com/",
        "jiohotstar" to "https://www.hotstar.com/in",
        "disney+ hotstar" to "https://www.hotstar.com/in",
    )

    fun channelLink(name: String, tv: TvListing): String? {
        val key = name.lowercase().trim()
        if (key == "fancode" && tv.source == "FanCode" && tv.url.startsWith("https://")) return tv.url
        return CHANNEL_SITES[key]
    }

    data class Listing(val url: String, val kickoff: Instant, val title: String)
    data class FanCodeItem(val url: String, val slug: String, val kickoff: Instant?, val competition: String)

    fun parseTeam(html: String): List<Listing> {
        val out = LinkedHashMap<String, Listing>()
        for (row in Jsoup.parse(html).select("tr.matchrow")) {
            val link = row.selectFirst("a[href^=/match/]") ?: continue
            val ts = row.selectFirst(".ts[dv]")?.attr("dv")?.toLongOrNull() ?: continue
            val href = link.attr("href").substringBefore("#")
            out.putIfAbsent(href, Listing(LST + href, Instant.ofEpochMilli(ts), link.attr("title")))
        }
        return out.values.toList()
    }

    fun parseCountry(html: String, country: String = COUNTRY): List<String> {
        for (row in Jsoup.parse(html).select("tr")) {
            val flag = row.selectFirst("span.flag") ?: continue
            if (flag.text().trim() != country) continue
            val cells = row.select("td")
            if (cells.size < 2) return emptyList()
            return cells.last()!!.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        }
        return emptyList()
    }

    private fun norm(s: String?) = (s ?: "").lowercase().filter { it.isLetterOrDigit() }

    fun matchListing(m: Match, listings: List<Listing>): Listing? {
        val names = setOf(norm(m.opponent.name), norm(m.opponent.short), norm(m.opponent.abbr)).filter { it.length >= 3 }
        return listings.firstOrNull { l ->
            val title = norm(l.title)
            abs(l.kickoff.epochSecond - m.kickoff.epochSecond) <= WINDOW_S && names.any { it in title || it.take(6) in title }
        }
    }

    /** {ESPN competition slug: FanCode schedule URLs} for the competitions FanCode lists now. */
    fun parseFanCodeTours(html: String): Map<String, List<String>> {
        val tours = LinkedHashMap<String, MutableList<String>>()
        for (a in Jsoup.parse(html).select("a[href*=/football/tour/]")) {
            val slug = a.attr("href").substringAfter("/football/tour/").substringBefore("/").substringBefore("?")
            if (slug.isEmpty() || "women" in slug) continue
            val url = "$FANCODE/football/tour/$slug/matches"
            for ((comp, pattern) in FANCODE_TOURS) {
                if (pattern.containsMatchIn(slug)) tours.getOrPut(comp) { mutableListOf() }.let { if (url !in it) it += url }
            }
        }
        return tours
    }

    private val FC_ID = Regex("\\{\"id\":(\\d+),\"teamType\"")
    private val FC_SLUG = Regex("\"matchSlug\":\"([a-z0-9-]+)\"")
    private val FC_START = Regex("\"startTime\":\"([^\"]+)\"")
    private val FC_TOUR = Regex("\"collectionId\":(\\d+)[^{}]*?\"collectionSlug\":\"([a-z0-9-]+)\"")

    private fun isBarca(slug: String) = "barcelona" in slug && "women" !in slug && "femeni" !in slug

    /** Barcelona fixtures on a FanCode tour page, from its embedded schedule JSON and its links. */
    fun parseFanCode(html: String, competition: String, tourUrl: String): List<FanCodeItem> {
        val tourSlug = tourUrl.substringAfter("/football/tour/").substringBefore("/")
        val seen = HashSet<String>()
        val out = mutableListOf<FanCodeItem>()
        for (m in FC_SLUG.findAll(html)) {
            val slug = m.groupValues[1]
            if (!isBarca(slug)) continue
            val id = FC_ID.findAll(html.substring(maxOf(0, m.range.first - 3000), m.range.first)).lastOrNull()?.groupValues?.get(1) ?: continue
            val full = "$slug-$id"
            if (!seen.add(full)) continue
            val after = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 1500))
            val tour = FC_TOUR.find(after)?.let { "${it.groupValues[2]}-${it.groupValues[1]}" } ?: tourSlug
            out += FanCodeItem("$FANCODE/football/tour/$tour/matches/$full/live-match-info", full,
                parseInstant(FC_START.find(after)?.groupValues?.get(1)), competition)
        }
        for (a in Jsoup.parse(html).select("a[href*=/matches/]")) {
            val href = a.attr("href").substringBefore("?")
            val slug = href.trimEnd('/').substringAfter("/matches/").substringBefore("/")
            if (!isBarca(slug) || !seen.add(slug)) continue
            out += FanCodeItem(if (href.startsWith("http")) href else FANCODE + href, slug, null, competition)
        }
        return out
    }

    fun matchFanCode(m: Match, items: List<FanCodeItem>): FanCodeItem? {
        val names = listOf(norm(m.opponent.short), norm(m.opponent.name)).filter { it.length >= 4 }
        return items.firstOrNull { item ->
            item.competition == m.competition.slug &&
                // The reverse fixture has the same opponent; only the kick-off tells them apart.
                (item.kickoff == null || abs(item.kickoff.epochSecond - m.kickoff.epochSecond) <= WINDOW_S) &&
                norm(item.slug.replace("barcelona", "")).let { slug -> names.any { it.take(6) in slug } }
        }
    }
}
