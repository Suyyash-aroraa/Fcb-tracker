package com.fcbtracker.app

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.fcbtracker.core.Snapshot
import com.fcbtracker.widget.FcbWidget
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val OUT = "build/screens"

/** Renders the real app and widget with live data (fetched in the test, no mock data). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-night-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var snapshot: Snapshot

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        snapshot = runBlocking {
            val repo = Data.repo(context)
            val s = repo.refresh(forceTv = true)
            (s.matches.flatMap { listOf(it.home, it.away) } + s.table?.rows.orEmpty().map { it.team }).distinctBy { it.id }.forEach { repo.crest(it) }
            s
        }
    }

    private fun settle() { compose.waitForIdle(); Thread.sleep(1500); compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }

    private fun waitFor(text: String) = compose.waitUntil(60_000) { compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(label: String) { compose.onAllNodes(hasText(label)).onLast().performClick(); settle() }

    @Test fun appScreens() {
        compose.setContent { FcbTheme { App() } }
        waitFor("Coming up"); compose.waitUntil(60_000) { compose.onAllNodesWithText("Leaders", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        settle(); Thread.sleep(2000); settle()
        compose.onRoot().captureRoboImage("$OUT/01-overview.png")

        tab("Matches"); compose.onRoot().captureRoboImage("$OUT/02-fixtures.png")
        compose.onAllNodes(hasText("Results")).onFirst().performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/03-results.png")

        tab("Squad"); Thread.sleep(3000); settle()
        compose.onRoot().captureRoboImage("$OUT/04-squad.png")
        compose.onAllNodes(hasText("Goals")).onFirst().performClick(); settle(); Thread.sleep(1500); settle()
        compose.onRoot().captureRoboImage("$OUT/05-squad-by-goals.png")
        val top = snapshot.squad.maxBy { it.stats.goals ?: 0 }.name
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(top)); settle()
        compose.onRoot().captureRoboImage("$OUT/05b-forwards.png")
        compose.onAllNodes(hasText(top)).onFirst().performClick(); settle(); Thread.sleep(1500); settle()
        compose.onRoot().captureRoboImage("$OUT/06-player-sheet.png")
        Espresso.pressBack(); settle()

        tab("Table"); compose.onRoot().captureRoboImage("$OUT/07-table.png")
        tab("News"); Thread.sleep(3000); settle()
        compose.onRoot().captureRoboImage("$OUT/08-news.png")

        // Next match: preview with head-to-head since 2020.
        tab("Overview")
        compose.onAllNodes(hasText("VS")).onFirst().performClick()
        waitFor("Meetings since 2020"); compose.waitUntil(60_000) { compose.onAllNodesWithText("Played").fetchSemanticsNodes().isNotEmpty() }
        settle(); Thread.sleep(1500); settle()
        compose.onRoot().captureRoboImage("$OUT/09-match-preview.png")
        compose.onAllNodes(hasText("Matches")).onFirst().performClick(); settle()

        // Last result's match centre.
        compose.onAllNodes(hasText("Match centre")).onFirst().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Summary").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Assist", substring = true).fetchSemanticsNodes().isNotEmpty() || snapshot.last?.scoreText == "0-0" }
        settle()
        compose.onRoot().captureRoboImage("$OUT/10-match-summary.png")
        compose.onAllNodes(hasText("Stats")).onFirst().performClick(); settle(); Thread.sleep(800); settle()
        compose.onRoot().captureRoboImage("$OUT/11-match-stats.png")
        compose.onAllNodes(hasText("Lineups")).onFirst().performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/12-match-lineups.png")
    }

    @Test fun widgetSizes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        for ((name, size) in listOf("small" to DpSize(150.dp, 150.dp), "wide" to DpSize(340.dp, 160.dp), "tall" to DpSize(340.dp, 270.dp))) {
            val views = runBlocking { FcbWidget().compose(context, size = size) }
            frame.removeAllViews()
            val v: View = views.apply(activity, frame)
            frame.addView(v, ViewGroup.LayoutParams((size.width.value * density).toInt(), (size.height.value * density).toInt()))
            shadowOf(Looper.getMainLooper()).idle()
            v.captureRoboImage("$OUT/widget-$name.png")
            Log.i("ScreensTest", "widget $name rendered")
        }
        assertTrue(snapshot.matches.isNotEmpty())
    }
}

/** The overview in the light theme. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-notnight-xxhdpi")
class LightThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overviewLight() {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        runBlocking { Data.repo(context).refresh(forceTv = true) }
        compose.setContent { FcbTheme { App() } }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Leaders", ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); Thread.sleep(3000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/13-overview-light.png")
        compose.onAllNodes(hasText("Squad")).onLast().performClick(); compose.waitForIdle(); Thread.sleep(3000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/14-squad-light.png")
    }
}

/** Matches on a narrow phone (360dp): full names, Barça's crest first on every row. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1400dp-night-xxhdpi")
class NarrowMatchesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun matchesNarrow() {
        // India time: the widest kick-off times ("12:30 am").
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        runBlocking { Data.repo(context).refresh() }
        compose.setContent { FcbTheme { App(startTab = Tab.Matches) } }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Fixtures").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); Thread.sleep(2500); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/15-fixtures-360dp.png")
        compose.onAllNodes(hasText("Results")).onFirst().performClick(); compose.waitForIdle(); Thread.sleep(2000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/16-results-360dp.png")
        compose.onAllNodes(hasText("Overview")).onLast().performClick(); compose.waitForIdle(); Thread.sleep(2500); compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("COMING UP")); compose.waitForIdle(); Thread.sleep(1500); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/17-coming-up-360dp.png")
        compose.onAllNodes(hasText("Table")).onLast().performClick(); compose.waitForIdle(); Thread.sleep(2000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/18-table-360dp.png")
    }
}
