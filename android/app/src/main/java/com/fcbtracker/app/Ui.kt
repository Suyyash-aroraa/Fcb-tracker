package com.fcbtracker.app

import com.fcbtracker.core.Match
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Text formatting shared by the app and the widget. Times are in the phone's time zone. */
object Ui {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val longFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())
    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private val SHORT = mapOf(
        "esp.1" to "LALIGA", "uefa.champions" to "UCL", "esp.copa_del_rey" to "Copa del Rey",
        "esp.super_cup" to "Supercopa", "club.friendly" to "Friendly", "esp.joan_gamper" to "Gamper",
    )

    fun compShort(m: Match) = SHORT[m.competition.slug] ?: m.competition.name.removePrefix("Spanish ")
    fun compName(m: Match) = m.competition.name.removePrefix("Spanish ")

    fun time(m: Match): String = m.kickoff.atZone(zone).format(timeFmt)
    fun longDate(m: Match): String = m.kickoff.atZone(zone).format(longFmt)

    fun dayLabel(m: Match, now: Instant = Instant.now()): String {
        val day = m.kickoff.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val name = when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(dayFmt)
        }
        return if (m.isUpcoming) "$name, ${time(m)}" else name
    }

    fun countdown(m: Match, now: Instant = Instant.now()): String {
        val d = Duration.between(now, m.kickoff)
        if (d.isNegative) return "Kick-off"
        return when {
            d.toDays() >= 2 -> "in ${d.toDays()} days"
            d.toHours() >= 1 -> "in ${d.toHours()}h ${d.toMinutesPart()}m"
            else -> "in ${d.toMinutes()}m"
        }
    }

    fun clock(m: Match): String = when {
        m.status.short?.contains("HT") == true || m.status.detail == "Halftime" -> "HT"
        !m.status.clock.isNullOrBlank() && m.status.clock != "0'" -> m.status.clock!!
        else -> m.status.short ?: ""
    }

    /** ESPN seasons start in July: a match in March 2024 belongs to 2023-24. */
    fun season(m: Match): String {
        val d = m.kickoff.atZone(java.time.ZoneOffset.UTC)
        val y = if (d.monthValue >= 7) d.year else d.year - 1
        return "$y-%02d".format((y + 1) % 100)
    }

    fun ordinal(n: Int) = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    fun ago(iso: String?, now: Instant = Instant.now()): String {
        val t = com.fcbtracker.core.parseInstant(iso) ?: return "never"
        val m = Duration.between(t, now).toMinutes()
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            m < 60 * 48 -> "Yesterday"
            else -> "${m / 1440} days ago"
        }
    }
}
