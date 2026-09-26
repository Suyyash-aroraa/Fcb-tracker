package com.fcbtracker.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fcbtracker.R
import com.fcbtracker.core.Match
import com.fcbtracker.core.MatchDetail
import com.fcbtracker.core.Snapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Sync.start(this)
        setContent { FcbTheme { App() } }
    }
}

data class UiState(
    val snapshot: Snapshot? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** The last result's details (scorers on the overview). */
    val lastDetail: MatchDetail? = null,
    val detail: MatchDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    /** The live match's details (scorers in the hero), saved by the live refresh. */
    val liveDetail: MatchDetail? = null,
    /** Head-to-head for the open match, since 2020. */
    val meetings: List<Match>? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Data.repo(app)
    private val _state = MutableStateFlow(UiState(snapshot = repo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var detailJob: Job? = null

    init {
        _state.value.snapshot?.last?.let { last -> _state.update { it.copy(lastDetail = repo.cachedDetail(last.id)) } }
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { repo.refresh() }
            _state.update {
                it.copy(refreshing = false, snapshot = result.getOrNull() ?: it.snapshot,
                    error = result.exceptionOrNull()?.let { e -> "Couldn't refresh: ${e.message}" })
            }
            _state.update { st -> st.copy(liveDetail = st.snapshot?.live?.let { repo.cachedDetail(it.id) }) }
            _state.value.snapshot?.last?.let { last ->
                runCatching { repo.detail(last) }.onSuccess { d -> _state.update { it.copy(lastDetail = d) } }
            }
            runCatching { Sync.refreshWidgets(getApplication()); Sync.plan(getApplication(), result.getOrNull()) }
        }
    }

    /** Open a match: fetch its summary now, and every 30 seconds while it is live. */
    fun open(m: Match) {
        detailJob?.cancel()
        _state.update { it.copy(detail = repo.cachedDetail(m.id)?.let { d -> d.copy(match = m) }, detailLoading = true, detailError = null, meetings = null) }
        detailJob = viewModelScope.launch {
            launch { runCatching { repo.meetings(m.opponent.id, m.id) }.onSuccess { ms -> _state.update { it.copy(meetings = ms) } } }
            var match = m
            while (isActive) {
                val r = runCatching { repo.detail(match) }
                r.onSuccess { d -> match = d.match; _state.update { it.copy(detail = d, detailLoading = false) } }
                    .onFailure { e -> _state.update { it.copy(detailLoading = false, detailError = e.message) } }
                if (!match.isLive && !(match.isUpcoming && repo.inLiveWindow(_state.value.snapshot).any { it.id == match.id })) break
                delay(30_000)
            }
        }
    }

    fun close() {
        detailJob?.cancel()
        _state.update { it.copy(detail = null, detailError = null, meetings = null) }
    }
}

enum class Tab(val label: String, val icon: Int, val selectedIcon: Int) {
    Overview("Overview", R.drawable.ic_house, R.drawable.ic_house_fill),
    Matches("Matches", R.drawable.ic_calendar_blank, R.drawable.ic_calendar_blank_fill),
    Squad("Squad", R.drawable.ic_users_three, R.drawable.ic_users_three_fill),
    Table("Table", R.drawable.ic_list_numbers, R.drawable.ic_list_numbers_fill),
    News("News", R.drawable.ic_newspaper, R.drawable.ic_newspaper_fill),
}

@Composable
fun App(vm: AppViewModel = viewModel(), startTab: Tab = Tab.Overview) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (Match) -> Unit = { m -> openId = m.id }
    val detailMatch = openId?.let { id ->
        state.detail?.match?.takeIf { it.id == id } ?: state.snapshot?.withTv?.find { it.id == id } ?: state.meetings?.find { it.id == id }
    }
    LaunchedEffect(openId) {
        if (openId != null && detailMatch != null && state.detail?.match?.id != openId) vm.open(detailMatch)
    }
    val reduce = reducedMotion()

    Box(Modifier.fillMaxSize().background(P.bg)) {
        if (openId != null && detailMatch != null) {
            BackHandler { openId = null; vm.close() }
            MatchScreen(detailMatch, state, onBack = { openId = null; vm.close() }, onOpen = { m -> vm.close(); openId = m.id })
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(state, onRefresh = vm::refresh)
                AnimatedContent(
                    targetState = tab, modifier = Modifier.weight(1f), label = "tab",
                    transitionSpec = {
                        if (reduce) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn(tween(200)) + slideInVertically(tween(250)) { 24 }) togetherWith fadeOut(tween(120))
                    },
                ) { t ->
                    when (t) {
                        Tab.Overview -> OverviewScreen(state, onRefresh = vm::refresh, onOpen = open, onTab = { tab = it })
                        Tab.Matches -> MatchesScreen(state, onRefresh = vm::refresh, onOpen = open)
                        Tab.Squad -> SquadScreen(state, onRefresh = vm::refresh)
                        Tab.Table -> TableScreen(state, onRefresh = vm::refresh)
                        Tab.News -> NewsScreen(state, onRefresh = vm::refresh)
                    }
                }
                NavigationBar(containerColor = P.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, size = 22.dp, tint = if (tab == t) P.onAccent else P.text2) },
                            label = { Text(t.label, style = T.small.copy(fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Medium)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = P.accent, selectedTextColor = P.text, unselectedTextColor = P.text2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, onRefresh: () -> Unit) {
    Column(Modifier.background(P.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Blaugrana mark
            Row(Modifier.size(width = 14.dp, height = 26.dp).clip(RoundedCornerShape(4.dp))) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Blau)); Box(Modifier.weight(1f).fillMaxHeight().background(Grana))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(buildAnnotatedString { append("FCB "); withStyle(SpanStyle(color = P.accentText)) { append("TRACKER") } },
                    style = T.display.copy(fontSize = 26.sp, letterSpacing = 0.4.sp), color = P.text)
                val s = state.snapshot
                Text(
                    when {
                        state.refreshing -> "Updating..."
                        s != null -> "Updated ${Ui.ago(s.updatedAt)}"
                        else -> "Not loaded yet"
                    },
                    style = T.small, color = P.text3,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(R.drawable.ic_arrow_clockwise, size = 22.dp, tint = if (state.refreshing) P.text3 else P.text2, description = "Refresh")
            }
        }
    }
}

fun android.content.Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
