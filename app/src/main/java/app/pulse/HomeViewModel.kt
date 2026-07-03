package app.pulse

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.Extractor
import app.pulse.core.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class HomeState(
    val loading: Boolean = true,
    val trending: List<StreamItem> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Extractor.trending() } }
            res.onSuccess { list -> _state.update { it.copy(loading = false, trending = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load") } }
        }
    }
}
