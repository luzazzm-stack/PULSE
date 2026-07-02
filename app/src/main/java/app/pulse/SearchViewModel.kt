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
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) { _state.update { it.copy(query = q) } }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null, searched = true) }
        job = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Extractor.searchMusic(q) } }
            res.onSuccess { list -> _state.update { it.copy(loading = false, results = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Search failed", results = emptyList()) } }
        }
    }
}
