package app.pulse.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.pulse.core.Extractor
import app.pulse.core.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Future

@Immutable
data class PlayerUi(
    val items: List<StreamItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasCurrent: Boolean = false,
    val error: String? = null,
    val videoMode: Boolean = false,
) {
    val current: StreamItem? get() = items.getOrNull(index)
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private var future: Future<MediaController>? = null
    private var released = false
    private var items: List<StreamItem> = emptyList()
    private var index = 0
    private var videoMode = false
    private var resolveJob: Job? = null

    private val _ui = MutableStateFlow(PlayerUi())
    val ui = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) next() }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            if (!released) { controller = f.get().also { it.addListener(listener) }; pushState() }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (true) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _ui.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0L), durationMs = if (c.duration > 0) c.duration else it.durationMs) }
                    delay(500)
                } else delay(1000)
            }
        }
    }

    fun playList(list: List<StreamItem>, startIndex: Int) {
        if (list.isEmpty()) return
        items = list
        index = startIndex.coerceIn(0, list.lastIndex)
        _ui.update { it.copy(items = list, index = index, hasCurrent = true) }
        resolveAndPlay()
    }

    fun playOne(item: StreamItem) = playList(listOf(item), 0)

    /** Play a locally-downloaded file directly (no extractor resolution). */
    fun playLocalFile(title: String, uploader: String, thumbnailUrl: String?, filePath: String) {
        val c = controller ?: return
        val item = StreamItem(url = filePath, title = title, uploader = uploader, durationSec = 0, thumbnailUrl = thumbnailUrl)
        items = listOf(item)
        index = 0
        _ui.update { it.copy(items = items, index = 0, hasCurrent = true, loading = false, error = null) }
        val mi = MediaItem.Builder()
            .setUri(Uri.fromFile(File(filePath)))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(uploader).setArtworkUri(thumbnailUrl?.let { Uri.parse(it) }).build()
            )
            .build()
        c.setMediaItem(mi)
        c.prepare()
        c.play()
    }

    fun addToQueue(item: StreamItem) {
        if (items.isEmpty()) { playOne(item); return }
        items = items + item
        _ui.update { it.copy(items = items) }
    }

    fun next() { if (index < items.lastIndex) { index++; _ui.update { it.copy(index = index) }; resolveAndPlay() } }
    fun prev() { if (index > 0) { index--; _ui.update { it.copy(index = index) }; resolveAndPlay() } }
    fun togglePlay() { val c = controller ?: return; if (c.isPlaying) c.pause() else c.play() }

    fun toggleVideoMode() {
        videoMode = !videoMode
        _ui.update { it.copy(videoMode = videoMode) }
        resolveAndPlay()
    }

    fun exoPlayer(): Player? = controller
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }

    private fun resolveAndPlay() {
        val item = items.getOrNull(index) ?: return
        resolveJob?.cancel()
        _ui.update { it.copy(loading = true, error = null) }
        resolveJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Extractor.streamInfo(item.url) } }
            val c = controller
            result.onSuccess { data ->
                val streamUrl = if (videoMode) (data.videoUrl ?: data.audioUrl) else data.audioUrl
                if (streamUrl != null && c != null) {
                    val mi = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist(item.uploader)
                                .setArtworkUri(item.thumbnailUrl?.let { Uri.parse(it) })
                                .build()
                        )
                        .build()
                    c.setMediaItem(mi)
                    c.prepare()
                    c.play()
                    _ui.update { it.copy(loading = false, error = null) }
                } else {
                    Log.e("PULSE", "No playable audio stream for ${item.url}")
                    _ui.update { it.copy(loading = false, error = "No playable stream for this track") }
                }
            }.onFailure { e ->
                Log.e("PULSE", "streamInfo failed for ${item.url}", e)
                _ui.update { it.copy(loading = false, error = "Couldn't load this track") }
            }
        }
    }

    private fun pushState() {
        val c = controller ?: return
        _ui.update {
            it.copy(
                items = items,
                index = index,
                isPlaying = c.isPlaying,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = if (c.duration > 0) c.duration else (items.getOrNull(index)?.durationSec?.times(1000) ?: 0L),
                hasCurrent = items.getOrNull(index) != null,
            )
        }
    }

    override fun onCleared() {
        released = true
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onCleared()
    }
}
