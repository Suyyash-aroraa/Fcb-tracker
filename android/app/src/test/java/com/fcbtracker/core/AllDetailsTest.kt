package com.fcbtracker.core

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class AllDetailsTest {
    @Test fun everyPlayedMatchLoads() = runTest(timeout = 5.minutes) {
        val repo = Repository(Files.createTempDirectory("fcb").toFile())
        val s = repo.refresh()
        val older = repo.meetings(s.next!!.opponent.id, s.next!!.id).take(4)
        for (m in s.played + older) {
            val r = runCatching { repo.detail(m) }
            println("${m.id} ${m.competition.slug} ${m.home.short} ${m.scoreText} ${m.away.short}: " +
                r.fold({ "ok events=${it.events.size} stats=${it.stats.size} lineups=${it.lineups.size}" }, { "FAIL $it" }))
        }
    }
}
