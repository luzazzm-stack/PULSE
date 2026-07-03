package app.pulse

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.Extractor
import app.pulse.core.HomeCard
import app.pulse.core.HomeShelf
import app.pulse.core.StreamItem
import app.pulse.core.YtMusic
import kotlinx.coroutines.Dispatchers
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

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val shelves = withContext(Dispatchers.IO) {
                val ytm = runCatching { YtMusic.home() }.getOrDefault(emptyList())
                if (ytm.isNotEmpty()) ytm
                else runCatching { Extractor.trending() }.getOrDefault(emptyList())
                    .let { if (it.isEmpty()) emptyList() else listOf(HomeShelf("Trending", it.map { s -> s.toCard() })) }
            }
            _state.update { it.copy(loading = false, shelves = shelves, error = if (shelves.isEmpty()) "Couldn't load home" else null) }
        }
    }
}

private fun StreamItem.toCard() = HomeCard(title, uploader, thumbnailUrl, Uri.parse(url).getQueryParameter("v"), null)
