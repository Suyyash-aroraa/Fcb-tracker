package com.fcbtracker.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/** Runs the phone's data layer against the real sources: no fixtures or mock data. */
class LiveSourcesTest {
    private val dir = Files.createTempDirectory("fcb").toFile()
    private val repo = Repository(dir)

    @Test
    fun refreshFetchesEverythingDirectly() = runTest(timeout = 3.minutes) {
        val s = repo.refresh(forceTv = true)
        println("errors: ${s.errors}")
        assertTrue("fixtures and results", s.matches.size > 5)
        assertTrue("every match involves Barcelona", s.matches.all { FCB_ESPN_ID in listOf(it.home.id, it.away.id) })
        assertEquals("sorted by kick-off", s.matches.map { it.date }.sorted(), s.matches.map { it.date })
        s.played.forEach { assertNotNull("${it.id} has a result", it.result); assertNotNull(it.scoreText) }
        assertEquals(20, s.table?.rows?.size)
        assertNotNull("Barcelona in the table", s.position)
        assertTrue("India TV found for an upcoming fixture", s.upcoming.take(6).any { it.tv != null })
        s.upcoming.take(6).forEach { m -> println("TV ${m.home.short} v ${m.away.short}: ${m.tv?.channels} ${m.tv?.url}") }
        s.withTv.mapNotNull { it.tv }.forEach { tv -> tv.channels.forEach { ch -> Tv.channelLink(ch, tv)?.let { assertTrue(it.startsWith("https://")) } } }

        // Squad with official photos, and news from the three sources.
        assertTrue("squad", s.squad.size >= 20)
        assertTrue("goalkeepers, defenders, midfielders, forwards", setOf("G", "D", "M", "F").all { g -> s.squad.any { it.pos == g } })
        assertTrue("official photos matched", s.squad.count { it.profile?.contains("fcbarcelona.com") == true } >= 15)
        println("squad ${s.squad.size}, official photos ${s.squad.count { it.profile != null }}, news ${s.news.groupingBy { it.origin }.eachCount()}")
        assertTrue("news", s.news.size >= 10)
        assertTrue("club news", s.news.any { it.origin == "official" })

        // Saved on the phone, and read back identically.
        assertEquals(s, repo.load())
    }

    @Test
    fun matchDetailGoalsAddUpToTheScore() = runTest(timeout = 3.minutes) {
        val s = repo.refresh()
        val last = s.last!!
        val d = repo.detail(last)
        assertTrue("team stats", d.stats.isNotEmpty())
        assertTrue("lineups", d.lineups.values.any { it.starters.size == 11 })
        val goals = d.events.filter { it.kind == "goal" || it.kind == "own-goal" }
        val home = goals.count { it.side == "home" }; val away = goals.count { it.side == "away" }
        assertEquals("goal events match ${last.scoreText}", "${d.match.home.score}-${d.match.away.score}", "$home-$away")
        assertNotNull("kept on the phone", repo.cachedDetail(last.id))
        assertNotNull("crest downloaded", repo.crest(last.opponent))

        // Head-to-head since 2020 from past seasons.
        val next = s.upcoming.first { it.competition.slug == "esp.1" }
        val meetings = repo.meetings(next.opponent.id, next.id)
        println("meetings with ${next.opponent.name}: ${meetings.size}")
        assertTrue("earlier seasons included", meetings.any { it.date < s.matches.first().date })
        assertTrue(meetings.all { it.date >= "2020-01-01" && next.opponent.id in listOf(it.home.id, it.away.id) })
    }
}
