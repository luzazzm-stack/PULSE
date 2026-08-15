package app.pulse

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.CategoryArtStore
import app.pulse.core.MoodCategory
import app.pulse.core.RecentSearchStore
import app.pulse.core.StreamItem
import app.pulse.core.YtMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /** Live type-ahead rows for the current (pre-submit) query. */
    val suggestions: List<String> = emptyList(),
    /** YT Music "Moods & genres" tiles for the idle page; empty until the once-per-process fetch lands. */
    val categories: List<MoodCategory> = emptyList(),
    /** Tile artwork resolved from YT's own curated category pages (name -> thumbnail URL); mirrors
     *  CategoryArtStore so cached art shows instantly and new resolves land as they finish. */
    val categoryArt: Map<String, String> = emptyMap(),
)

class SearchViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var suggestJob: Job? = null
    /** Guards the moods & genres fetch so a session hits FEmusic_moods_and_genres at most once —
     *  reset only on failure so an offline first visit can still succeed later. */
    private var categoriesRequested = false
    /** Names with an art resolve in flight or done — main-thread confined (requestCategoryArt runs
     *  from composition, the failure rollback on viewModelScope's Main dispatcher), so no locking. */
    private val artRequested = HashSet<String>()
    /** At most ~2 art resolves hit the network at once — a burst of visible tiles must not fire a
     *  dozen category-page fetches that starve the user's actual search. */
    private val artSemaphore = Semaphore(2)

    init {
        // Recents live in RecentSearchStore (filesDir JSON) so they survive process death. The store
        // isn't wired into PulseApp.onCreate — init it lazily here with the Application context, off
        // the main thread, then mirror its flow into SearchState so the screen keeps one state object.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecentSearchStore.init(app) }
            RecentSearchStore.recents.collect { list -> _state.update { it.copy(recents = list) } }
        }
        // Same lazy-init + mirror for the category-art cache (each collect suspends forever, so the
        // two stores need their own launch).
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CategoryArtStore.init(app) }
            CategoryArtStore.art.collect { m -> _state.update { it.copy(categoryArt = m) } }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        // Every keystroke kills the in-flight suggestion request — only the debounce survivor fires.
        suggestJob?.cancel()
        val trimmed = q.trim()
        // Suggestions only exist pre-submit (results own the screen after) and for 2+ chars — one
        // char produces near-random completions and wastes a request per keystroke.
        if (_state.value.searched || trimmed.length < 2) {
            if (_state.value.suggestions.isNotEmpty()) _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)   // debounce: only a pause in typing reaches the network
            val sugg = withContext(Dispatchers.IO) { runCatching { YtMusic.searchSuggestions(trimmed) }.getOrDefault(emptyList()) }
            // The box may have moved on while the request was in flight — stale suggestions under a
            // newer query flash the wrong completions, so drop them.
            if (_state.value.query.trim() == trimmed) _state.update { it.copy(suggestions = sugg) }
        }
    }

    fun searchFor(q: String) {
        // Set the query directly (not via setQuery) so tapping a recent/suggestion/category doesn't
        // spawn a pointless suggestion fetch for a search that's about to run anyway.
        _state.update { it.copy(query = q) }
        search()
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        addRecent(q)
        job?.cancel()
        suggestJob?.cancel()
        val videos = _state.value.tab == 2
        _state.update { it.copy(loading = true, error = null, searched = true, suggestions = emptyList()) }
        job = viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { YtMusic.search(q, videos) } }
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
    fun clearQuery() {
        job?.cancel(); suggestJob?.cancel()
        _state.update { it.copy(query = "", results = emptyList(), searched = false, loading = false, error = null, suggestions = emptyList()) }
    }

    fun clearRecents() {
        viewModelScope.launch(Dispatchers.IO) { RecentSearchStore.init(app); RecentSearchStore.clear() }
    }

    private fun addRecent(q: String) {
        viewModelScope.launch(Dispatchers.IO) { RecentSearchStore.init(app); RecentSearchStore.add(q) }
    }

    /** Moods & genres for the idle page. Called every time the search tab composes but fetches at
     *  most once per process (the list is effectively static for a session); the screen shows its
     *  fixed fallback tiles until — or in case — this lands. */
    fun loadCategories() {
        if (categoriesRequested) return
        categoriesRequested = true
        viewModelScope.launch {
            val cats = withContext(Dispatchers.IO) { runCatching { YtMusic.moodsAndGenres() }.getOrDefault(emptyList()) }
            if (cats.isNotEmpty()) _state.update { it.copy(categories = cats) }
            // Empty means the fetch hard-failed (offline / rate-limited) — allow the next tab visit
            // to retry instead of locking this session onto the hardcoded fallback forever.
            else categoriesRequested = false
        }
    }

    /** Resolve tile artwork for one category, lazily (each visible tile fires this once via
     *  LaunchedEffect). Idempotent per name; at most ~2 resolves run concurrently; never throws.
     *  Art = first non-blank card thumbnail from YT's OWN curated page for the category, falling back
     *  to the first non-blank search-result thumbnail only when the page yields nothing. */
    fun requestCategoryArt(name: String) {
        if (name.isBlank() || _state.value.categoryArt.containsKey(name) || !artRequested.add(name)) return
        viewModelScope.launch {
            val ok = try {
                // withPermit releases in a finally even when the resolve throws or is cancelled.
                artSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        CategoryArtStore.init(app)
                        // The disk cache may have landed after the early-return check above.
                        if (CategoryArtStore.art.value.containsKey(name)) true
                        else {
                            val url = resolveArt(name)
                            if (!url.isNullOrBlank()) { CategoryArtStore.put(name, url); true } else false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Emma/Search", "category art for \"$name\" failed", e)
                false
            }
            // Failed (offline / rate-limited / no thumbnails at all) — drop the guard so a later tab
            // visit can retry instead of locking the tile onto the colored fallback for the session.
            if (!ok) artRequested.remove(name)
        }
    }

    private fun resolveArt(name: String): String? {
        val cat = _state.value.categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (cat?.browseId != null) {
            val fromPage = runCatching { YtMusic.categoryPage(cat.browseId, cat.params) }
                .getOrDefault(emptyList())
                .firstNotNullOfOrNull { shelf ->
                    shelf.cards.firstNotNullOfOrNull { c -> c.thumbnailUrl?.takeIf { it.isNotBlank() } }
                }
            if (fromPage != null) return fromPage
        }
        // No click target (fixed fallback tile) or the curated page had no usable thumbnails — a plain
        // search for the name is the best remaining guess.
        return runCatching { YtMusic.search(name) }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { it.thumbnailUrl?.takeIf { u -> u.isNotBlank() } }
    }
}
