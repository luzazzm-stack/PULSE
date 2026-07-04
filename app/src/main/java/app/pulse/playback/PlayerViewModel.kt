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
    val source: String = "Emma",
    val shuffle: Boolean = false,
    val repeat: Int = 0,   // 0 = off, 1 = repeat all, 2 = repeat one
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
    private var shuffle = false
    private var repeat = 0
    private var order: List<Int> = emptyList()   // play order over `items` (identity, or shuffled with current first)
    private var resolveJob: Job? = null

    private val _ui = MutableStateFlow(PlayerUi())
    val ui = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // repeat-one: loop the already-buffered track (seek), never re-fetch over the network
                if (repeat == 2) { controller?.seekTo(0); controller?.play() } else advance(auto = true)
            }
        }
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

    fun playList(list: List<StreamItem>, startIndex: Int, source: String = "Emma") {
        if (list.isEmpty()) return
        items = list
        index = startIndex.coerceIn(0, list.lastIndex)
        rebuildOrder()
        _ui.update { it.copy(items = list, index = index, hasCurrent = true, source = source) }
        resolveAndPlay()
    }

    fun playOne(item: StreamItem) = playList(listOf(item), 0)

    /** Play a locally-downloaded file directly (no extractor resolution). */
    fun playLocalFile(title: String, uploader: String, thumbnailUrl: String?, filePath: String) {
        val c = controller ?: return
        val item = StreamItem(url = filePath, title = title, uploader = uploader, durationSec = 0, thumbnailUrl = thumbnailUrl)
        items = listOf(item)
        index = 0
        rebuildOrder()
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
        rebuildOrder()
        _ui.update { it.copy(items = items) }
    }

    fun next() = advance(auto = false)

    /** Build the play order over `items`: identity, or (shuffled) the current track first then the rest shuffled once. */
    private fun rebuildOrder() {
        val idx = items.indices.toMutableList()
        if (shuffle && items.size > 1) { idx.remove(index); idx.shuffle(); idx.add(0, index) }
        order = idx
    }

    private fun goTo(i: Int) { index = i; _ui.update { it.copy(index = index) }; resolveAndPlay() }

    /** Step forward through the play order, honoring shuffle + repeat. auto=true means the track ended on its own. */
    private fun advance(auto: Boolean) {
        if (items.isEmpty()) return
        val pos = order.indexOf(index).coerceAtLeast(0)
        val nextPos = when {
            pos < order.lastIndex -> pos + 1
            repeat == 1 -> 0                 // wrap to the start of the order when repeat-all
            else -> return                   // end of order, no repeat: stop (manual next is a no-op, not a restart)
        }
        goTo(order[nextPos])
    }

    fun prev() {
        if (items.isEmpty()) return
        val pos = order.indexOf(index).coerceAtLeast(0)
        val prevPos = when {
            pos > 0 -> pos - 1
            repeat == 1 -> order.lastIndex   // wrap to the end of the order when repeat-all
            else -> return
        }
        goTo(order[prevPos])
    }

    fun toggleShuffle() { shuffle = !shuffle; rebuildOrder(); _ui.update { it.copy(shuffle = shuffle) } }
    fun cycleRepeat() { repeat = (repeat + 1) % 3; _ui.update { it.copy(repeat = repeat) } }
    fun togglePlay() { val c = controller ?: return; if (c.isPlaying) c.pause() else c.play() }

    fun toggleVideoMode() {
        videoMode = !videoMode
        _ui.update { it.copy(videoMode = videoMode) }
        resolveAndPlay(controller?.currentPosition ?: 0L)  // keep the current position when switching audio<->video
    }

    fun exoPlayer(): Player? = controller
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }

    private fun resolveAndPlay(resumePositionMs: Long = 0L) {
        val item = items.getOrNull(index) ?: return
        resolveJob?.cancel()
        // Downloaded/offline tracks store a raw file path in `url` — play them directly, never via the network extractor.
        val local = runCatching { File(item.url) }.getOrNull()?.takeIf { it.exists() }
        if (local != null) {
            val c = controller ?: return
            val mi = MediaItem.Builder()
                .setUri(Uri.fromFile(local))
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(item.title).setArtist(item.uploader).setArtworkUri(item.thumbnailUrl?.let { Uri.parse(it) }).build()
                )
                .build()
            c.setMediaItem(mi); c.prepare()
            if (resumePositionMs > 0L) c.seekTo(resumePositionMs)
            c.play()
            _ui.update { it.copy(loading = false, error = null) }
            return
        }
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
                    if (resumePositionMs > 0L) c.seekTo(resumePositionMs)
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
