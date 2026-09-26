package com.fcbtracker.app

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
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
@Config(sdk = [34], qualifiers = "w411dp-h2400dp-night-xxhdpi")
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

    @Test fun appScreens() {
        compose.setContent { FcbTheme { App() } }
        waitFor("Coming up"); settle()
        compose.onRoot().captureRoboImage("$OUT/1-overview.png")

        compose.onNode(hasText("Matches")).performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/2-fixtures.png")
        compose.onNode(hasText("Results", substring = true)).performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/3-results.png")

        compose.onNode(hasText("Table")).performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/4-table.png")

        // Last result's match centre, loaded from ESPN when opened.
        compose.onNode(hasText("Overview")).performClick(); settle()
        compose.onNode(hasText("Last result", ignoreCase = true)).performClick()
        waitFor("Summary"); compose.waitUntil(60_000) { compose.onAllNodesWithText("GOAL").fetchSemanticsNodes().isNotEmpty() || snapshot.last?.scoreText == "0-0" }
        settle()
        compose.onRoot().captureRoboImage("$OUT/5-match-summary.png")
        compose.onNode(hasText("Stats")).performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/6-match-stats.png")
        compose.onNode(hasText("Lineups")).performClick(); settle()
        compose.onRoot().captureRoboImage("$OUT/7-match-lineups.png")
    }

    @Test fun widgetSizes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        for ((name, size) in listOf("small" to DpSize(130.dp, 130.dp), "wide" to DpSize(320.dp, 130.dp), "tall" to DpSize(320.dp, 200.dp))) {
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
