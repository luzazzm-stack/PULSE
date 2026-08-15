package app.pulse.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File

/** Recent search queries (most recent first, cap 10), persisted to filesDir/recent_searches.json so
 *  they survive process death. Unlike FavoritesStore/PlaylistStore this is NOT wired into
 *  PulseApp.onCreate — SearchViewModel init()s it lazily on first use with the Application context it
 *  already holds, so init is @Synchronized (it can race between the ViewModel's mirror-collect and an
 *  addRecent both launched on Dispatchers.IO). */
object RecentSearchStore {

    private const val MAX = 10

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private var loaded = false
    private lateinit var file: File

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, "recent_searches.json")
        _recents.value = runCatching { load() }.getOrDefault(emptyList())
        loaded = true
    }

    /** Push [query] to the front; case-insensitive dedupe keeps "Adele" and "adele" from stacking. */
    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _recents.update { (listOf(q) + it.filterNot { r -> r.equals(q, ignoreCase = true) }).take(MAX) }
        persist()
    }

    fun clear() {
        _recents.value = emptyList()
        persist()
    }

    private fun persist() {
        // runCatching also swallows the lateinit throw if someone ever persists before init — a lost
        // write beats a crash for something as low-stakes as search history.
        runCatching { file.writeText(JSONArray(_recents.value).toString()) }
    }

    private fun load(): List<String> {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }.take(MAX)
    }
}
