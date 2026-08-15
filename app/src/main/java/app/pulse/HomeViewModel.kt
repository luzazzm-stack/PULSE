package app.pulse

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.AuthStore
import app.pulse.core.Extractor
import app.pulse.core.HomeCard
import app.pulse.core.HomeShelf
import app.pulse.core.StreamItem
import app.pulse.core.YtMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class HomeState(
    val loading: Boolean = true,
    val shelves: List<HomeShelf> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        // Load on start AND whenever the connected state flips — the cookie decrypts asynchronously at cold start
        // and the user can log in/out — so the home always reflects the account. StateFlow emits the current value
        // first, which does the initial load.
        viewModelScope.launch { AuthStore.connectedFlow.collect { load() } }
    }

    fun load() {
        loadJob?.cancel()   // supersede any in-flight load so the latest (e.g. personalized) result wins
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            // home() retries, pads thin feeds with Charts/New Releases itself, and THROWS only when YouTube
            // was truly unreachable (offline / rate-limit HTML / 5xx) — so a successful-but-empty list means
            // "feed is genuinely empty", not "network died". Don't collapse the two like getOrDefault() did.
            val result = withContext(Dispatchers.IO) { runCatching { YtMusic.home() } }
            result.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e   // never swallow coroutine cancellation
                Log.w("Emma/Home", "home load failed", e)
                // Keep whatever shelves an earlier load produced — a transient failure on refresh (e.g. the
                // post-login reload) shouldn't blank an already-working screen.
                _state.update { it.copy(loading = false, error = "Couldn't reach YouTube Music. Check your connection and retry.") }
                return@launch
            }
            val shelves = result.getOrThrow()
            _state.update { it.copy(loading = false, shelves = shelves, error = if (shelves.isEmpty()) "YouTube Music returned an empty home feed." else null) }
        }
    }
}
