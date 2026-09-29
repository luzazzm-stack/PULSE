package app.pulse.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import app.pulse.core.StreamData
import app.pulse.core.StreamItem
import app.pulse.core.StreamResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Future
import kotlin.random.Random

// After this many back-to-back track failures (player errors + failed resolutions) playback stops with
// a visible error instead of chewing through — and with repeat-all, endlessly wrapping — the queue.
private const val MAX_CONSECUTIVE_FAILURES = 3

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
    private var order: List<Int> = emptyList()   // play order over `items`; the prefix before orderPos is the actually-played history
    private var orderPos = 0                     // position of the current track within `order` (never trust indexOf: queues can hold duplicates)
    private var resolveJob: Job? = null
    // Set true by clearAll() so the STATE_ENDED that clearing the queue emits doesn't trigger auto-advance.
    private var suppressAutoAdvance = false
    // A tap in the first moments after launch used to silently no-op because the MediaController future
    // hadn't connected yet. Park the requested action here and replay it the moment the connection lands.
    private var pendingOnConnect: (() -> Unit)? = null
    // Player errors used to unconditionally advance(); with repeat-all the order wraps, so an expired
    // URL or a network blip death-spiraled silently (resolve -> error -> advance forever). Instead:
    // one fresh re-resolve of the CURRENT track per error, plus a consecutive-failure circuit breaker.
    private var retriedCurrentAfterError = false
    private var consecutiveFailures = 0
    // Warms StreamResolver's cache with the upcoming tracks' streams while the current one plays, so
    // advancing usually swaps straight to a ready URL instead of idling on the network — that idle gap
    // (player IDLE, radio quiet, screen off) is exactly where Doze liked to kill playback after a few songs.
    private var prefetchJob: Job? = null
    // The upcoming track, already handed to the player as a SECOND media item so ExoPlayer rolls straight
    // into it. Without this every song ended in STATE_ENDED, Media3 dropped the service out of the
    // foreground, and the next track then had to restart foreground from the background while the network
    // resolve ran — with the screen off the system refused or froze the app, and playback died after a
    // few songs. [pos] is the track's position in `order` (or in [wrapOrder] when it opens a repeat-all cycle).
    private class Queued(val token: String, val pos: Int, val wrapOrder: List<Int>?)
    private var queued: Queued? = null
    private var queueJob: Job? = null
    private var queueSeq = 0

    private val _ui = MutableStateFlow(PlayerUi())
    val ui = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    // The track actually loaded: the failure streak is over, and this track has earned a
                    // fresh error-retry (a later error means its URL expired again, e.g. after a long pause).
                    consecutiveFailures = 0
                    retriedCurrentAfterError = false
                    prefetchNext()
                    queueNext()
                }
                Player.STATE_ENDED -> {
                    if (suppressAutoAdvance) { suppressAutoAdvance = false; return }  // queue was just cleared (Close), don't advance
                    // repeat-one: loop the already-buffered track (seek), never re-fetch over the network.
                    // If the URL expired mid-loop the seek errors out and onPlayerError re-resolves it fresh.
                    if (repeat == 2) { controller?.seekTo(0); controller?.play() } else advance(auto = true)
                }
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player moved onto the queued next track by itself (AUTO) or via advance()'s seek (SEEK):
            // adopt it as the current track. REPEAT (repeat-one looping) and PLAYLIST_CHANGED (our own
            // setMediaItem) are not moves to the queued track.
            val q = queued ?: return
            if (mediaItem?.mediaId != q.token) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
            queued = null
            q.wrapOrder?.let { order = it }
            orderPos = q.pos.coerceIn(0, order.lastIndex)
            index = order[orderPos]
            retriedCurrentAfterError = false
            Log.i("PULSE", "gapless -> orderPos=$orderPos/${order.lastIndex}")
            // Drop the finished track so the player holds just [current] again, ready for the next queueNext().
            controller?.let { c -> if (c.currentMediaItemIndex > 0) c.removeMediaItems(0, c.currentMediaItemIndex) }
            _ui.update { it.copy(index = index, loading = false, error = null) }
            // A gapless move never passes through STATE_READY again, so line up the following track here.
            prefetchNext()
            queueNext()
        }
        override fun onPlayerError(error: PlaybackException) {
            Log.e("PULSE", "player error for ${items.getOrNull(index)?.url}", error)
            // An active resolve means the user already navigated AWAY from the item that errored —
            // typical case: track A stalls on an expired URL, the user presses next, and A's deferred
            // error surfaces while track B is still resolving. Acting on it here would count a failure
            // against B's streak, cancel B's in-flight resolve, and (worse) re-resolve B with A's
            // playback position as the resume point — B would start minutes in, or insta-END if A's
            // position exceeds B's duration. The stale error is moot: the resolve replaces the media
            // item anyway, so just drop it.
            if (resolveJob?.isActive == true) return
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                // Several tracks in a row died: that's a dead network / dead session, not one bad track.
                resolveJob?.cancel()
                _ui.update { it.copy(loading = false, error = "Playback stopped — check your connection") }
                return
            }
            if (!retriedCurrentAfterError) {
                // Classic cause: an expired googlevideo URL. Re-resolve THIS track once with a fresh URL
                // and pick up where playback stopped. (MediaController masks seeks, so after repeat-one's
                // seekTo(0) this correctly resumes at 0, not at the end of the track.)
                retriedCurrentAfterError = true
                val resume = controller?.currentPosition?.coerceAtLeast(0L) ?: _ui.value.positionMs
                _ui.update { it.copy(error = null) }
                resolveAndPlay(resume, forceRefresh = true)
            } else {
                // A fresh URL didn't help either -> the track itself is bad; tell the user and move on.
                _ui.update { it.copy(error = "Couldn't play this track") }
                advance(auto = true)
            }
        }
    }

    /**
     * Receives the service's broadcasts fired when a lock-screen / Bluetooth / notification button is
     * pressed: the ForwardingPlayer in PlaybackService can't navigate the (single-item) timeline itself,
     * so it delegates skip + stop back here to the real manual queue.
     */
    private val commandListener = object : MediaController.Listener {
        override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (command.customAction) {
                CMD_ADVANCE -> advance(auto = false)
                CMD_PREV -> prev()
                CMD_STOP -> clearAll()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(controller: MediaController) {
            // The service died under us (system kill, or a full teardown while we were alive): a
            // disconnected MediaController is released and silently no-ops every call, so keeping it
            // would make every future play tap do nothing forever. Drop it and reconnect — binding
            // restarts PlaybackService, and pendingOnConnect replays any request that raced in.
            if (released) return
            this@PlayerViewModel.controller = null
            future?.let { MediaController.releaseFuture(it) }
            future = null
            connectController()
        }
    }

    private fun connectController() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).setListener(commandListener).buildAsync()
        future = f
        f.addListener({
            // `future === f` guards against a stale connect landing after a disconnect already
            // swapped in a newer attempt (the stale controller would be a dead one).
            if (!released && future === f) {
                controller = f.get().also {
                    it.addListener(listener)
                    it.repeatMode = if (repeat == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF   // a restarted service starts at OFF
                }
                pushState()
                // Replay a play request that raced the connection (first tap after a cold launch,
                // or one that arrived while we were rebuilding after a service death).
                pendingOnConnect?.let { pending -> pendingOnConnect = null; pending() }
            }
        }, ContextCompat.getMainExecutor(app))
    }

    init {
        connectController()
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
        suppressAutoAdvance = false
        retriedCurrentAfterError = false
        // Starting a fresh queue is explicit user intent that any old failure streak is irrelevant
        // (same as togglePlay's IDLE retry). Without this a streak left by the END of a previous
        // queue — where no STATE_READY ever follows to clear it — pre-trips the circuit breaker on
        // the very first hiccup of a brand-new, healthy queue.
        consecutiveFailures = 0
        invalidatePrefetch()
        items = list
        index = startIndex.coerceIn(0, list.lastIndex)
        startCycle()
        _ui.update { it.copy(items = list, index = index, hasCurrent = true, source = source) }
        resolveAndPlay()
    }

    fun playOne(item: StreamItem) = playList(listOf(item), 0)

    /** "Shuffle all": play [list] in shuffle mode, starting from a random track. */
    fun shuffleAll(list: List<StreamItem>, source: String) {
        if (list.isEmpty()) return
        if (!shuffle) {
            shuffle = true
            _ui.update { it.copy(shuffle = true) }
        }
        playList(list, Random.nextInt(list.size), source)
    }

    fun addToQueue(item: StreamItem) {
        if (items.isEmpty()) { playOne(item); return }
        items = items + item
        val newIdx = items.lastIndex
        // Splice the newcomer into the REMAINING portion of the existing order only. The old full
        // rebuild re-shuffled everything on every add: already-played tracks re-entered the upcoming
        // order and the queue the user was looking at visibly re-scrambled.
        order = if (shuffle) {
            val at = orderPos + 1 + Random.nextInt(order.size - orderPos)   // anywhere strictly after the current pointer
            order.toMutableList().apply { add(at, newIdx) }
        } else {
            order + newIdx   // sequential order stays the identity
        }
        invalidatePrefetch()   // the upcoming-next may just have changed
        dropQueued()
        prefetchNext()
        queueNext()
        _ui.update { it.copy(items = items) }
    }

    fun next() = advance(auto = false)

    /**
     * Start a play cycle at `index`. Sequential: identity order with the pointer on `index` (tracks
     * before it count as played, so prev walks into them). Shuffle: `index` pinned first, the rest
     * shuffled ONCE — the upcoming order then stays fixed for the whole cycle.
     */
    private fun startCycle() {
        if (shuffle && items.size > 1) {
            order = listOf(index) + items.indices.filter { it != index }.shuffled()
            orderPos = 0
        } else {
            order = items.indices.toList()
            orderPos = index
        }
    }

    private fun playAt(pos: Int) {
        if (order.isEmpty()) return
        orderPos = pos.coerceIn(0, order.lastIndex)
        index = order[orderPos]
        retriedCurrentAfterError = false   // every track gets its own one-shot expired-URL retry
        _ui.update { it.copy(index = index) }
        resolveAndPlay()
    }

    /** Step forward through the play order, honoring shuffle + repeat. auto=true means the track ended on its own. */
    private fun advance(auto: Boolean) {
        Log.i("PULSE", "advance(auto=$auto): orderPos=$orderPos/${order.lastIndex} items=${items.size} shuffle=$shuffle repeat=$repeat")
        if (items.isEmpty()) return
        // The next track is already loaded in the player: jump onto it (instant, no network). seekTo(index, pos)
        // is used because the service's QueuePlayer turns seekToNext* into another ADVANCE broadcast.
        val c = controller
        if (queued != null && c != null && c.mediaItemCount > 1 && c.playbackState != Player.STATE_IDLE) {
            c.seekTo(c.currentMediaItemIndex + 1, 0L)
            c.play()
            return
        }
        // On a USER skip, silence the outgoing track the instant of the tap: letting it keep playing
        // under the loading state made every skip feel broken ("I pressed next and the old song kept
        // going"). Auto-advance doesn't need this — the track already ended. Only pause on the branches
        // that actually navigate, so a dead-end Next (end of queue, no repeat) stays a true no-op.
        when {
            orderPos < order.lastIndex -> { if (!auto) controller?.pause(); playAt(orderPos + 1) }
            repeat == 1 -> {
                // Repeat-all wrap-around starts a FRESH cycle: under shuffle that's a full reshuffle
                // (this is the one moment re-shuffling is correct); sequential just starts over on top.
                order = if (shuffle && items.size > 1) {
                    items.indices.shuffled().toMutableList().apply {
                        if (first() == index) add(removeAt(0))   // don't open the new cycle with the track that just closed the old one
                    }
                } else items.indices.toList()
                if (!auto) controller?.pause()
                playAt(0)
            }
            else -> return   // end of order, no repeat: stop (manual next is a no-op, not a restart)
        }
    }

    fun prev() {
        if (items.isEmpty()) return
        // Media convention: more than 3s into the track, "previous" restarts the current track instead of skipping.
        if ((controller?.currentPosition ?: 0L) > 3000L) { controller?.seekTo(0); return }
        when {
            // prev() is always user-initiated: silence the outgoing track immediately (see advance()).
            orderPos > 0 -> { controller?.pause(); playAt(orderPos - 1) }   // the order prefix IS the played history, so shuffle-prev retraces actual plays
            repeat == 1 -> { controller?.pause(); playAt(order.lastIndex) }
            else -> controller?.seekTo(0)          // at the first track: restart it rather than a dead no-op
        }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        if (items.isNotEmpty() && order.isNotEmpty()) {
            if (shuffle) {
                // Shuffle ON: history + current stay exactly where they are; only the not-yet-played
                // remainder is shuffled, so nothing already heard re-enters this cycle.
                order = order.take(orderPos + 1) + order.drop(orderPos + 1).shuffled()
            } else {
                // Shuffle OFF: back to the natural queue order, pointer on the current track.
                order = items.indices.toList()
                orderPos = index
            }
        }
        invalidatePrefetch()   // the upcoming-next likely changed
        dropQueued()
        prefetchNext()
        queueNext()
        _ui.update { it.copy(shuffle = shuffle) }
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        // Repeat-one loops inside the player itself, so a finished track never passes through STATE_ENDED
        // (the gap that knocked playback out of the foreground). The queued next track has to go: with
        // repeat-one it isn't next, and leaving repeat-one (or entering repeat-all) changes what is.
        controller?.repeatMode = if (repeat == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        dropQueued()
        queueNext()
        _ui.update { it.copy(repeat = repeat) }
    }

    fun togglePlay() {
        val c = controller ?: return
        // Decide on playback INTENT (playWhenReady), not c.isPlaying: while the next track buffers,
        // isPlaying is false even though playback will start — pausing must still work in that window.
        val playingIntent = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
        when {
            playingIntent -> c.pause()
            // ENDED keeps playWhenReady, so play() alone is a dead no-op there — restart the track instead.
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            // After a fatal error (or the failure circuit breaker) the player sits in IDLE, where play()
            // can't do anything without a prepare — kick a fresh resolve of the current track instead,
            // resuming where it stopped. Also resets the failure streak: the user asked to try again.
            c.playbackState == Player.STATE_IDLE && items.isNotEmpty() -> {
                consecutiveFailures = 0
                resolveAndPlay(_ui.value.positionMs)
            }
            else -> c.play()
        }
    }

    /** Fully stop and clear the queue (fired by the notification Close button). Resets the UI so the mini-player disappears. */
    fun clearAll() {
        suppressAutoAdvance = true   // set BEFORE clearing so the STATE_ENDED from clearMediaItems() doesn't auto-advance
        resolveJob?.cancel()
        invalidatePrefetch()
        dropQueued()
        pendingOnConnect = null
        consecutiveFailures = 0
        retriedCurrentAfterError = false
        items = emptyList(); index = 0; order = emptyList(); orderPos = 0; videoMode = false; shuffle = false; repeat = 0
        controller?.let { it.repeatMode = Player.REPEAT_MODE_OFF; it.stop(); it.clearMediaItems() }   // stops playback + removes the media notification
        _ui.value = PlayerUi()
    }

    fun toggleVideoMode() {
        videoMode = !videoMode
        // No prefetch invalidation needed: cached StreamData carries BOTH audio and video URLs, and
        // the re-resolve below is a cache hit — so an audio<->video switch is now near-instant too.
        _ui.update { it.copy(videoMode = videoMode) }
        resolveAndPlay(controller?.currentPosition ?: 0L)  // keep the current position when switching audio<->video
    }

    fun exoPlayer(): Player? = controller
    fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0L)) }

    private fun resolveAndPlay(resumePositionMs: Long = 0L, forceRefresh: Boolean = false) {
        val item = items.getOrNull(index) ?: return
        resolveJob?.cancel()
        dropQueued()   // setMediaItem below replaces the whole playlist; a queued next would be stale anyway
        val c = controller
        if (c == null) {
            // Controller still connecting (cold start): park the whole request — the old silent `return`
            // here was why the first tap after launch sometimes never started anything.
            _ui.update { it.copy(loading = true, error = null) }
            pendingOnConnect = { resolveAndPlay(resumePositionMs, forceRefresh) }
            return
        }
        // Offline sources play directly, never via the network extractor: downloads and device-library
        // tracks store a MediaStore content:// uri in `url` (pre-Q or not-yet-published downloads, a raw path).
        // A download moved to shared storage while it sat in this queue plays from where it went.
        val directUri = localUri(item)
        if (directUri != null) {
            c.setMediaItem(mediaItemFor(item, directUri)); c.prepare()
            if (resumePositionMs > 0L) c.seekTo(resumePositionMs)
            c.play()
            _ui.update { it.copy(loading = false, error = null) }
            return
        }
        _ui.update { it.copy(loading = true, error = null) }
        resolveJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // After a player error the cached URL is exactly what died — drop it so the resolver
                // can't hand it straight back.
                if (forceRefresh) StreamResolver.evict(item.url)
                // Cache hit returns instantly (prefetch warmed it); a miss takes the fast iOS path with
                // NewPipe as fallback. One retry after a short pause: network hiccups are routinely
                // transient, and a raw first-try exception used to leave the tap looking like it did nothing.
                runCatching { StreamResolver.resolve(item.url) }
                    .recoverCatching { delay(750); StreamResolver.resolve(item.url) }
            }
            result.onSuccess { data ->
                startResolved(item, data, resumePositionMs)
            }.onFailure { e ->
                Log.e("PULSE", "streamInfo failed for ${item.url}", e)
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    _ui.update { it.copy(loading = false, error = "Playback stopped — check your connection") }
                } else {
                    // Bad track (or a deleted local file whose retry fell through here): surface it and
                    // move on; the failure counter above keeps this from spiraling around the queue.
                    _ui.update { it.copy(loading = false, error = "Couldn't load this track") }
                    advance(auto = true)
                }
            }
        }
    }

    /** Hand a resolved stream to the player and START it — every skip path funnels through here (or the
     *  direct-uri/local paths above), so an advanced-to track can never arrive waiting for a manual unpause. */
    private fun startResolved(item: StreamItem, data: StreamData, resumePositionMs: Long) {
        // Staleness guard: this can run LATE — as a queued coroutine continuation that slipped past
        // resolveJob.cancel() (the cancel only bites at suspension points), or as a pendingOnConnect
        // replay. If the notification's Close (clearAll) emptied the queue, or the user jumped to a
        // different track, in the meantime, a stale setMediaItem+play here would resurrect playback —
        // and re-post the just-dismissed notification — after teardown. Bail unless this item is
        // still the current track; the counter/advance branches below must not fire for it either.
        if (items.getOrNull(index) !== item) return
        val streamUrl = if (videoMode) (data.videoUrl ?: data.audioUrl) else data.audioUrl
        if (streamUrl == null) {
            // Genuinely no stream in the resolved data — distinct from "controller missing" (below),
            // which used to be collapsed into this same misleading message.
            Log.e("PULSE", "No playable stream for ${item.url}")
            consecutiveFailures++
            _ui.update { it.copy(loading = false, error = "No playable stream for this track") }
            if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) advance(auto = true)
            return
        }
        val c = controller
        if (c == null) {
            // Controller not connected (resolution outran the cold-start connection): park the already-
            // resolved stream and start it the moment the connection lands — no re-resolve, no fake error.
            pendingOnConnect = { startResolved(item, data, resumePositionMs) }
            return
        }
        c.setMediaItem(mediaItemFor(item, Uri.parse(streamUrl)))
        c.prepare()
        if (resumePositionMs > 0L) c.seekTo(resumePositionMs)
        // Explicit start regardless of the paused/ended/idle state the skip was requested in: a
        // paused-then-skip is still expected to play the new track.
        c.play()
        _ui.update { it.copy(loading = false, error = null) }
    }

    /**
     * Warm StreamResolver's cache with the next TWO upcoming tracks while the current one plays.
     * Best-effort and invisible on failure — the normal resolve path just does the work later.
     * StreamData carries both the audio and video URLs, so a videoMode toggle can't stale the cache;
     * invalidation triggers (queue mutation / shuffle toggle) only cancel in-flight warming, because
     * cache entries are keyed by watch URL and stay valid no matter how the order changes around them.
     */
    private fun prefetchNext() {
        if (order.isEmpty()) return
        val targets = (1..2).mapNotNull { step ->
            val pos = orderPos + step
            when {
                pos <= order.lastIndex -> order[pos]
                // Sequential repeat-all wraps predictably; a shuffle wrap reshuffles first, so there
                // is no knowable "next" to warm there.
                repeat == 1 && !shuffle -> order[pos % order.size]
                else -> null
            }
        }
            .mapNotNull { items.getOrNull(it) }
            .filter { it.url.startsWith("http") }   // local files / content uris: nothing to resolve
            .distinctBy { it.url }
        if (targets.isEmpty()) return
        if (prefetchJob?.isActive == true) return
        prefetchJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (t in targets) {
                    if (!isActive) break
                    runCatching { StreamResolver.resolve(t.url) }   // warms the shared cache; failure just means the live resolve pays later
                }
            }
        }
    }

    private fun invalidatePrefetch() {
        prefetchJob?.cancel(); prefetchJob = null
    }

    /**
     * Hand the upcoming track to the player as a second media item (see [queued]). Uses the same
     * resolver cache prefetchNext() warms, so this is usually instant. Best-effort: when it can't
     * (resolve failed, repeat-one, end of the queue), the old ENDED -> advance() path still works.
     */
    private fun queueNext() {
        val c = controller ?: return
        if (queued != null || queueJob?.isActive == true) return
        if (resolveJob?.isActive == true || repeat == 2 || items.isEmpty() || order.isEmpty()) return
        if (c.mediaItemCount != 1 || c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) return
        val pos: Int
        var wrap: List<Int>? = null
        when {
            orderPos < order.lastIndex -> pos = orderPos + 1
            repeat == 1 -> {
                // Opening the next repeat-all cycle: build its order now (same rule as advance()) so the
                // first track of the new cycle can be queued too.
                wrap = if (shuffle && items.size > 1) {
                    items.indices.shuffled().toMutableList().apply { if (first() == index) add(removeAt(0)) }
                } else items.indices.toList()
                pos = 0
            }
            else -> return
        }
        val item = items.getOrNull((wrap ?: order)[pos]) ?: return
        val seq = ++queueSeq
        val curIndex = index
        val curPos = orderPos
        val token = "q$seq"
        queueJob = viewModelScope.launch {
            val uri: Uri = localUri(item) ?: run {
                val data = withContext(Dispatchers.IO) { runCatching { StreamResolver.resolve(item.url) }.getOrNull() } ?: return@launch
                val url = (if (videoMode) (data.videoUrl ?: data.audioUrl) else data.audioUrl) ?: return@launch
                Uri.parse(url)
            }
            val cc = controller ?: return@launch
            // Anything that moved (skip, new queue, shuffle, close) since this started makes the pick stale.
            if (seq != queueSeq || index != curIndex || orderPos != curPos || cc.mediaItemCount != 1) return@launch
            if (cc.playbackState == Player.STATE_IDLE || cc.playbackState == Player.STATE_ENDED) return@launch
            cc.addMediaItem(mediaItemFor(item, uri, token))
            queued = Queued(token, pos, wrap)
            Log.i("PULSE", "queued next: pos=$pos ${item.title}")
        }
    }

    /** Take the queued next track back out of the player (the order or repeat mode changed under it). */
    private fun dropQueued() {
        queueSeq++
        queueJob?.cancel(); queueJob = null
        if (queued == null) return
        queued = null
        controller?.let { c ->
            val from = c.currentMediaItemIndex + 1
            if (c.mediaItemCount > from) c.removeMediaItems(from, c.mediaItemCount)
        }
    }

    /** Downloads and on-device tracks play straight from storage; null for anything that needs resolving. */
    private fun localUri(item: StreamItem): Uri? {
        val location = app.pulse.download.DownloadManager.currentLocation(item.url)
        return when {
            location.startsWith("content://") -> Uri.parse(location)
            else -> runCatching { File(location) }.getOrNull()?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
        }
    }

    private fun mediaItemFor(item: StreamItem, uri: Uri, mediaId: String = ""): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.uploader)
                    .setArtworkUri(item.thumbnailUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    private fun pushState() {
        val c = controller ?: return
        _ui.update {
            it.copy(
                items = items,
                index = index,
                // Playback INTENT, not c.isPlaying: raw isPlaying is false for the whole buffering window
                // after a skip, which made every advanced-to track look like it arrived paused (and a tap
                // on the seemingly-paused button then couldn't pause it). IDLE/ENDED still show paused.
                isPlaying = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED,
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
