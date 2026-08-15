package app.pulse.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/** Category-tile artwork (mood/genre name -> thumbnail URL), persisted to filesDir/category_art.json
 *  so tiles show pictures instantly on later launches instead of re-resolving each name over the
 *  network. Like RecentSearchStore this is NOT wired into PulseApp.onCreate — SearchViewModel init()s
 *  it lazily on Dispatchers.IO, so init is @Synchronized (the mirror-collect and the first
 *  requestCategoryArt can race). A corrupt or missing file just yields an empty map. */
object CategoryArtStore {

    private val _art = MutableStateFlow<Map<String, String>>(emptyMap())
    val art: StateFlow<Map<String, String>> = _art.asStateFlow()

    private var loaded = false
    private lateinit var file: File

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, "category_art.json")
        _art.value = runCatching { load() }.getOrDefault(emptyMap())
        loaded = true
    }

    /** @Synchronized because SearchViewModel deliberately runs up to two art resolvers at once
     *  (Semaphore(2)) — unsynchronized, their persist() writeTexts could interleave on the same file
     *  and tear the JSON, silently dropping the whole disk cache on the next launch. Same monitor as
     *  init(), so a put can't race the initial load either. */
    @Synchronized
    fun put(name: String, url: String) {
        if (name.isBlank() || url.isBlank()) return
        _art.update { it + (name to url) }
        persist()
    }

    private fun persist() {
        // runCatching also swallows the lateinit throw if someone ever persists before init — a lost
        // write beats a crash for something as low-stakes as tile artwork.
        runCatching {
            val o = JSONObject()
            for ((k, v) in _art.value) o.put(k, v)
            file.writeText(o.toString())
        }
    }

    private fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val o = JSONObject(file.readText())
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optString(k)
            if (k.isNotBlank() && v.isNotBlank()) out[k] = v
        }
        return out
    }
}
