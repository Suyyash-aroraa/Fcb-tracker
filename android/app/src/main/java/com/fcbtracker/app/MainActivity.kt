package com.fcbtracker.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val detail: MatchDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Data.repo(app)
    private val _state = MutableStateFlow(UiState(snapshot = repo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var detailJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { repo.refresh() }
            _state.update {
                it.copy(refreshing = false, snapshot = result.getOrNull() ?: it.snapshot,
                    error = result.exceptionOrNull()?.let { e -> "Couldn't refresh: ${e.message}" })
            }
            runCatching { Sync.refreshWidgets(getApplication()); Sync.plan(getApplication(), result.getOrNull()) }
        }
    }

    /** Open a match: fetch its summary now, and every 30 seconds while it is live. */
    fun open(m: Match) {
        detailJob?.cancel()
        _state.update { it.copy(detail = repo.cachedDetail(m.id)?.let { d -> d.copy(match = m) }, detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
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
        _state.update { it.copy(detail = null, detailError = null) }
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (Match) -> Unit = { m -> openId = m.id }

    val detailMatch = openId?.let { id -> state.detail?.match?.takeIf { it.id == id } ?: state.snapshot?.withTv?.find { it.id == id } }
    LaunchedEffect(openId) {
        if (openId != null && detailMatch != null && state.detail?.match?.id != openId) vm.open(detailMatch)
    }
    if (openId != null && detailMatch != null) {
        BackHandler { openId = null; vm.close() }
        MatchScreen(detailMatch, state, onBack = { openId = null; vm.close() }, onOpen = open)
    } else {
        HomeScreen(state, tab, onTab = { tab = it }, onRefresh = vm::refresh, onOpen = open)
    }
}

fun android.content.Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
