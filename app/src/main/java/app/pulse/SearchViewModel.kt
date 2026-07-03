package app.pulse

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.Extractor
import app.pulse.core.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<StreamItem> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
    val recents: List<String> = emptyList(),
    val tab: Int = 0,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) { _state.update { it.copy(query = q) } }

    fun searchFor(q: String) {
        _state.update { it.copy(query = q) }
        search()
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        addRecent(q)
        job?.cancel()
        val videos = _state.value.tab == 2
        _state.update { it.copy(loading = true, error = null, searched = true) }
        job = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Extractor.searchMusic(q, videos) } }
            res.onSuccess { list -> _state.update { it.copy(loading = false, results = list) } }
                .onFailure { e ->
                    android.util.Log.e("Emma/Search", "search failed", e)
                    _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName, results = emptyList()) }
                }
        }
    }

    /** Switch the results tab (Top/Songs/Videos/Albums/Artists) and re-run the search. */
    fun setTab(i: Int) {
        if (_state.value.tab == i) return
        _state.update { it.copy(tab = i) }
        if (_state.value.searched && _state.value.query.isNotBlank()) search()
    }

    /** Return to the idle screen (recents + browse) from results. */
    fun clearQuery() { job?.cancel(); _state.update { it.copy(query = "", results = emptyList(), searched = false, loading = false, error = null) } }

    fun clearRecents() { _state.update { it.copy(recents = emptyList()) } }

    private fun addRecent(q: String) {
        _state.update { it.copy(recents = (listOf(q) + it.recents.filterNot { r -> r.equals(q, true) }).take(8)) }
    }
}
