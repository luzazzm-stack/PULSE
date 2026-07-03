package app.pulse.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File

/** Liked tracks (by watch URL), persisted to filesDir/favorites.json. */
object FavoritesStore {

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    private var loaded = false
    private lateinit var file: File

    fun init(context: Context) {
        if (loaded) return
        file = File(context.filesDir, "favorites.json")
        _liked.value = runCatching { load() }.getOrDefault(emptySet())
        loaded = true
    }

    fun toggle(url: String) {
        _liked.update { if (url in it) it - url else it + url }
        persist()
    }

    private fun persist() {
        runCatching { file.writeText(JSONArray(_liked.value.toList()).toString()) }
    }

    private fun load(): Set<String> {
        if (!file.exists()) return emptySet()
        val a = JSONArray(file.readText())
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }
}
