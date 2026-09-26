package com.fcbtracker.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** fcbarcelona.com: official player photos and profile links, and first-team news (same as fcb/official.py). */
object Official {
    private const val BASE = "https://www.fcbarcelona.com"
    const val PLAYERS = "$BASE/en/football/first-team/players"
    const val NEWS = "$BASE/en/football/first-team/news"

    data class OfficialPlayer(val name: String, val last: String, val number: String?, val photo: String?, val url: String)

    private fun Element.pick(sel: String) = select(sel).joinToString(" ") { it.text().trim() }.trim()

    private fun photo(node: Element, size: Int): String? {
        val src = node.selectFirst("[data-img-src]")?.attr("data-img-src")?.takeIf { it.isNotBlank() }
            ?: node.selectFirst("[data-image-src]")?.attr("data-image-src")?.substringBefore("?")?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
        return src?.let { "$it?width=$size&height=$size" }
    }

    private fun abs(href: String) = if (href.startsWith("http")) href else BASE + href

    fun parsePlayers(html: String): List<OfficialPlayer> = Jsoup.parse(html).select("a.team-person").mapNotNull { card ->
        val first = card.pick(".team-person__first-name"); val last = card.pick(".team-person__last-name")
        if (first.isEmpty() && last.isEmpty()) return@mapNotNull null
        OfficialPlayer("$first $last".trim(), last, card.pick(".team-person__number").ifEmpty { null }, photo(card, 400), abs(card.attr("href")))
    }

    fun norm(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
        .lowercase().replace("-", " ").split(" ").filter { it.isNotBlank() }.joinToString(" ")

    /** Attach official photos and profile links to ESPN's squad. */
    fun merge(squad: List<Player>, official: List<OfficialPlayer>): List<Player> {
        val byNumber = official.filter { it.number != null }.associateBy { it.number }
        return squad.map { p ->
            var o = byNumber[p.number]
            // Shirt numbers change between seasons; require the surname to agree as well.
            if (o != null && norm(o.last).split(" ").last() != norm(p.name).split(" ").last() && norm(o.last) !in norm(p.name)) o = null
            o = o ?: official.firstOrNull { norm(it.name) == norm(p.name) }
            if (o != null) p.copy(headshot = o.photo ?: p.headshot, profile = o.url) else p
        }
    }

    private fun published(label: String, now: Instant): String? {
        val l = label.trim().lowercase()
        Regex("(\\d+)\\s*(min|minute|hr|hour|day|week)s?\\s+ago").find(l)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2]) { "min", "minute" -> ChronoUnit.MINUTES; "hr", "hour" -> ChronoUnit.HOURS; "day" -> ChronoUnit.DAYS; else -> ChronoUnit.WEEKS }
            return now.minus(if (unit == ChronoUnit.WEEKS) n * 7 else n, if (unit == ChronoUnit.WEEKS) ChronoUnit.DAYS else unit).toString()
        }
        val d = l.replace(Regex("^.*published date\\s*"), "")
        for (f in listOf("d MMM yy", "d MMM yyyy", "d MMMM yyyy", "MMM d, yyyy")) {
            runCatching { return LocalDate.parse(d, DateTimeFormatter.ofPattern(f, Locale.ENGLISH)).atStartOfDay().toInstant(ZoneOffset.UTC).toString() }
        }
        return null
    }

    fun parseNews(html: String, limit: Int = 20, now: Instant = Instant.now()): List<NewsItem> {
        val out = LinkedHashMap<String, NewsItem>()
        for (card in Jsoup.parse(html).select("a.news-hero, a.thumbnail--news")) {
            val href = card.attr("href")
            if ("/news/" !in href) continue
            val prev = out[href]
            if (prev != null) {
                // The lead story repeats further down, sometimes with the image the hero lacked.
                if (prev.image == null) out[href] = prev.copy(image = photo(card, 800))
                continue
            }
            val title = card.pick(".thumbnail__title").ifEmpty { card.pick(".news-hero__title") }
            if (title.isEmpty()) continue
            out[href] = NewsItem(title, card.pick(".thumbnail__subtitle").ifEmpty { null }, abs(href), photo(card, 800),
                "FC Barcelona", published(card.pick("time"), now), "official")
            if (out.size >= limit) break
        }
        return out.values.toList()
    }
}

/** Google News RSS: the last week of Barça headlines from many publishers (same as fcb/google_news.py). */
object GoogleNews {
    const val FEED_URL = "https://news.google.com/rss/search?q=%22FC+Barcelona%22+OR+%22Bar%C3%A7a%22+when%3A7d&hl=en-US&gl=US&ceid=US:en"

    fun parse(xml: String, limit: Int = 24): List<NewsItem> {
        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val seen = HashSet<String>()
        return doc.select("item").mapNotNull { item ->
            val source = item.selectFirst("source")?.text()?.trim()
            var title = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (source != null && title.endsWith(" - $source")) title = title.dropLast(source.length + 3)
            if (title.isEmpty() || !seen.add(title.lowercase())) return@mapNotNull null
            val published = item.selectFirst("pubDate")?.text()?.let {
                runCatching { java.time.ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
            }
            NewsItem(title, null, item.selectFirst("link")?.text() ?: return@mapNotNull null, null, source, published, "google")
        }.take(limit)
    }
}
