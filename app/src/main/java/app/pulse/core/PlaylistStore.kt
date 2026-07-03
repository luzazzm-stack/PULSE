package app.pulse.core

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Immutable
data class Playlist(val id: String, val name: String, val tracks: List<StreamItem>)

/** Local, user-created playlists persisted to filesDir/playlists.json. */
object PlaylistStore {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private var loaded = false
    private lateinit var storeFile: File

    fun init(context: Context) {
        if (loaded) return
        storeFile = File(context.filesDir, "playlists.json")
        _playlists.value = runCatching { load() }.getOrDefault(emptyList())
        loaded = true
    }

    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        _playlists.update { it + Playlist(id, name.ifBlank { "New playlist" }, emptyList()) }
        persist()
        return id
    }

    fun addTrack(playlistId: String, track: StreamItem) {
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId && p.tracks.none { it.url == track.url }) p.copy(tracks = p.tracks + track) else p
            }
        }
        persist()
    }

    fun delete(playlistId: String) {
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        _playlists.value.forEach { p ->
            val tracks = JSONArray()
            p.tracks.forEach { t ->
                tracks.put(JSONObject().apply {
                    put("url", t.url); put("title", t.title); put("uploader", t.uploader)
                    put("dur", t.durationSec); put("thumb", t.thumbnailUrl ?: JSONObject.NULL)
                })
            }
            arr.put(JSONObject().apply { put("id", p.id); put("name", p.name); put("tracks", tracks) })
        }
        runCatching { storeFile.writeText(arr.toString()) }
    }

    private fun load(): List<Playlist> {
        if (!storeFile.exists()) return emptyList()
        val arr = JSONArray(storeFile.readText())
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val tArr = p.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until tArr.length()).map { j ->
                val t = tArr.getJSONObject(j)
                StreamItem(
                    url = t.getString("url"), title = t.getString("title"), uploader = t.getString("uploader"),
                    durationSec = t.optLong("dur", 0), thumbnailUrl = if (t.isNull("thumb")) null else t.optString("thumb"),
                )
            }
            Playlist(p.getString("id"), p.getString("name"), tracks)
        }
    }
}
