package app.pulse.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.util.Log
import app.pulse.core.AppSettings
import app.pulse.core.Extractor
import app.pulse.core.NewPipeDownloader
import app.pulse.core.SettingsStore
import app.pulse.core.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads YouTube streams (MP4 max / M4A audio / MP3 transcoded) with progress; persists across launches.
 * Work happens in app storage, then the finished file is published to the user's download folder — the one picked
 * in Settings, else Download/Emma ([PublicStore]) — so it's a normal file the user owns, not locked inside the app.
 */
object DownloadManager {

    private const val CHUNK_BYTES = 1L shl 20   // 1 MiB per ranged request
    private const val WORKERS = 4               // parallel connections per file
    private const val CHUNK_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // HTTP/1.1 on purpose: googlevideo throttles each connection to about playback speed, so the ranged
    // workers need a TCP connection each — over HTTP/2, OkHttp would multiplex them all onto one.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    // AndroidLame wraps a single global native encoder, so overlapping transcodes corrupt each other — serialize them.
    private val transcodeMutex = Mutex()
    // Downloads in their network phase (resolve + fetch), capped by the "Max simultaneous downloads" setting. Only
    // that phase holds a slot — an MP3 waiting for the encoder mustn't keep the next song from downloading. The
    // turnstile is a fair mutex, so queued downloads start in the order they were added.
    private val activeFetches = MutableStateFlow(0)
    private val turnstile = Mutex()
    // Live settings: the simultaneous-download cap, the Wi-Fi-only switch and the download folder picked in Settings.
    private lateinit var settings: StateFlow<AppSettings>
    // Whether the phone is on Wi-Fi (or Ethernet) right now — "Download over Wi-Fi only" holds queued downloads until it is.
    private val _onWifi = MutableStateFlow(true)
    val onWifi: StateFlow<Boolean> = _onWifi.asStateFlow()
    @Volatile private var connectivity: ConnectivityManager? = null
    // Serializes the maintenance passes (migrate / reconcile / sweep), which rewrite records outside any download.
    private val maintenanceMutex = Mutex()
    // Per-id download coroutine, so remove() can cancel an in-flight download.
    private val jobs = ConcurrentHashMap<String, Job>()
    // Per-id token of the ONE attempt allowed to write that id's record. remove() drops it and a retry replaces it,
    // so an attempt still unwinding after cancel (blocked in a read, mid-transcode) can neither resurrect a removed
    // download nor clobber the attempt that replaced it.
    private val live = ConcurrentHashMap<String, Any>()
    private val attemptSeq = AtomicLong(0)
    // Old app-storage path -> published location, for downloads migrated this session: a playback queue built before
    // the move (below Android 10 the storage grant can arrive mid-queue) still names the old path, whose file is gone.
    private val moved = ConcurrentHashMap<String, String>()

    /** Where a download that was at [location] lives now — itself unless it was moved to shared storage this session. */
    fun currentLocation(location: String): String = moved[location] ?: location
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private var loaded = false
    private lateinit var storeFile: File
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (loaded) return
        val startedAt = System.currentTimeMillis()
        appContext = context.applicationContext
        storeFile = File(context.filesDir, "downloads.json")
        val quarantine = File(context.filesDir, "downloads.json.bad")
        val restored = runCatching { load() }
        if (restored.isFailure && storeFile.exists()) {
            // Keep the unreadable file for recovery rather than letting the next persist() overwrite it.
            Log.e("PULSE/DL", "downloads.json unreadable — kept as ${quarantine.name}", restored.exceptionOrNull())
            storeFile.renameTo(quarantine)
        }
        _items.value = restored.getOrDefault(emptyList())
        settings = SettingsStore.flow(appContext).stateIn(scope, SharingStarted.Eagerly, AppSettings())
        watchNetwork(appContext)
        loaded = true
        scope.launch {
            maintenanceMutex.withLock {
                // The real settings — the StateFlow still holds its placeholder until DataStore's first read lands.
                val s = forgetUnusableFolder(SettingsStore.flow(appContext).first())
                migrateNow(s)
                reconcileNow()
                // Unreferenced finished files only go while the records are trustworthy: records lost to an unreadable
                // downloads.json (now or on an earlier launch) must not turn the songs they pointed at into "orphans".
                sweepPrivateDir(startedAt, everything = restored.isSuccess && !quarantine.exists())
            }
        }
        // Downloads still waiting when the process died (e.g. held for Wi-Fi) pick up again; ones that were mid-download
        // stay Failed for a manual retry, so a download that brings the app down can't loop on every launch.
        _items.value.filter { it.status == DlStatus.Queued }.forEach { startJob(appContext, it) }
    }

    fun enqueue(context: Context, url: String, title: String, uploader: String, thumbnailUrl: String?, format: DlFormat) {
        val id = (Uri.parse(url).getQueryParameter("v") ?: url.hashCode().toString()) + "_" + format.ext
        val existing = _items.value.firstOrNull { it.id == id }
        if (existing != null && existing.status != DlStatus.Failed) return
        existing?.artPath?.let { runCatching { File(it).delete() } }   // a retry replaces the failed record and its cover
        startJob(context.applicationContext, DownloadItem(id, url, title, uploader, thumbnailUrl, format, DlStatus.Queued))
    }

    /** Starts the one live attempt for [rec] (new, a retry, or resumed after a restart); it waits as Queued for a slot. */
    private fun startJob(app: Context, rec: DownloadItem) {
        val id = rec.id
        val token = Any()
        live[id] = token   // from here on, only this attempt may write the record
        upsert(rec, persist = true, token = token)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching { download(app, rec, token) }.onFailure { e ->
                    if (e !is CancellationException) Log.e("PULSE", "download failed", e)
                    fail(rec, token)
                }
            } finally {
                live.remove(id, token)
                jobs.remove(id, coroutineContext.job)
            }
        }
        jobs.put(id, job)?.cancel()
        job.start()
    }

    fun remove(item: DownloadItem) {
        live.remove(item.id)               // any attempt still running may no longer write this record
        jobs.remove(item.id)?.cancel()     // stop wasting network/CPU on a download the user removed
        // The UI's copy can predate the latest write (e.g. the Completed filePath) — delete what the record holds now.
        val current = _items.value.firstOrNull { it.id == item.id } ?: item
        _items.update { list -> list.filterNot { it.id == item.id } }
        persist()
        scope.launch {   // a MediaStore delete is IPC — keep it off the main thread
            current.filePath?.let { PublicStore.delete(appContext, it) }
            current.artPath?.let { File(it).delete() }   // the side-car cover goes with the media
        }
    }

    /**
     * Moves finished downloads still in app storage — everything from before v0.12.2, or a pre-Android-10
     * download made before the storage permission was granted — out to Download/Emma. Safe to call again:
     * anything already public, or that can't be published yet, is left as it is.
     */
    fun migrateToPublic() {
        scope.launch { maintenanceMutex.withLock { migrateNow(SettingsStore.flow(appContext).first()) } }
    }

    /**
     * Drops Completed records whose file is gone. Downloads are ordinary files in Download/Emma now, so the user
     * can delete them from the Files app — and a dead row that "plays" into an error is worse than no row.
     */
    fun reconcile() {
        scope.launch { maintenanceMutex.withLock { reconcileNow() } }
    }

    private fun migrateNow(s: AppSettings) {
        val privateRoot = privateDir(appContext).absolutePath
        for (item in _items.value) {
            val path = item.filePath ?: continue
            if (item.status != DlStatus.Completed || !path.startsWith(privateRoot)) continue
            val src = File(path).takeIf { it.exists() } ?: continue
            // Before v0.12.2 an "M4A" (and a video-less "MP4") download saved whatever audio stream was best — usually
            // WebM/Opus. Publish those under the container they really are, not a name other players would reject.
            val ext = if (isWebm(src)) "webm" else item.format.ext
            val published = publish(appContext, src, item, s, ext) ?: continue
            // Removed (or changed) while we copied — the public copy is ours to clean up.
            if (replaceFilePath(item.id, path, published)) {
                moved[path] = published
                src.delete()
            } else {
                PublicStore.delete(appContext, published)
            }
        }
    }

    private fun reconcileNow() {
        val gone = _items.value.filter { d -> d.status == DlStatus.Completed && d.filePath?.let { !PublicStore.exists(appContext, it) } == true }
        if (gone.isEmpty()) return
        Log.i("PULSE/DL", "dropping ${gone.size} download(s) whose file was deleted outside the app")
        val keys = gone.mapTo(HashSet()) { it.id to it.filePath }
        _items.update { list -> list.filterNot { (it.id to it.filePath) in keys } }
        persist()
        gone.forEach { d -> d.artPath?.let { File(it).delete() } }
    }

    /**
     * Deletes app-storage leftovers of attempts killed with the process: always in-progress .part/.tmp files; and, when
     * [everything] (the records are trustworthy), any other file no record references — covers of removed downloads,
     * finished media whose publish copy was cut short. Only files older than this process, so a download started
     * meanwhile is never touched; a record's own file (e.g. a pre-Q download awaiting the permission) never is.
     */
    private fun sweepPrivateDir(startedAt: Long, everything: Boolean) {
        val referenced = _items.value.flatMapTo(HashSet()) { listOfNotNull(it.filePath, it.artPath) }
        privateDir(appContext).listFiles()?.forEach { f ->
            val inProgress = f.name.endsWith(".part") || f.name.endsWith(".tmp")
            if (f.absolutePath !in referenced && (everything || inProgress) && f.lastModified() < startedAt) f.delete()
        }
    }

    /** Atomically repoints record [id] from [old] to [new]; false if it was removed or changed meanwhile. */
    private fun replaceFilePath(id: String, old: String, new: String): Boolean {
        var swapped = false
        _items.update { list ->
            swapped = false
            list.map { if (it.id == id && it.filePath == old) { swapped = true; it.copy(filePath = new) } else it }
        }
        if (swapped) persist()
        return swapped
    }

    private suspend fun download(context: Context, start: DownloadItem, token: Any) {
        val dir = privateDir(context).apply { mkdirs() }
        // Per-attempt names: an attempt still unwinding after remove/retry can't touch its replacement's files.
        // In-progress files end in .part/.tmp, which init()'s sweep may delete; finished media it never does.
        val stem = "${start.id}-${attemptSeq.incrementAndGet()}"
        fun report(p: Float) = upsert(start.copy(status = DlStatus.Downloading, progress = p), persist = false, token = token)

        val isMp3 = start.format == DlFormat.MP3
        val source = File(dir, if (isMp3) "$stem.src.tmp" else "$stem.${start.format.ext}.part")
        val fetched = withFetchSlot {
            upsert(start.copy(status = DlStatus.Downloading, progress = 0f), persist = true, token = token)
            val streamUrl = resolveStream(start) ?: return@withFetchSlot false
            // MP3: the download fills the first half of the bar, the transcode the second.
            val share = if (isMp3) 0.5f else 1f
            runCatching { fetchMedia(streamUrl, source) { p -> report(p * share) } }
                .getOrElse { e -> source.delete(); throw e }
                .also { ok -> if (!ok) source.delete() }
        }
        if (!fetched) { fail(start, token); return }
        val artPath = fetchArt(dir, stem, start)

        if (!isMp3) {
            val media = File(dir, "$stem.${start.format.ext}")
            if (!source.renameTo(media)) { source.delete(); fail(start.copy(artPath = artPath), token); return }
            complete(context, start, token, media, artPath)
            return
        }

        // YouTube never serves MP3 — transcode the downloaded source on-device. The source is always removed.
        val part = File(dir, "$stem.mp3.part")
        try {
            runCatching {
                transcodeMutex.withLock {
                    if (live[start.id] !== token) throw CancellationException("download removed")
                    // Throwing from the progress callback aborts a transcode the user no longer wants.
                    Mp3Transcoder.transcode(source, part) { p -> if (!report(0.5f + p * 0.5f)) throw CancellationException("download removed") }
                }
            }
                // Carry the already-fetched cover into the Failed record: remove() deletes only what the record
                // references, so a bare fail(start) (artPath = null) would strand the jpg in storage forever.
                .onFailure { e ->
                    if (e !is CancellationException) Log.e("PULSE", "mp3 transcode failed", e)
                    part.delete(); fail(start.copy(artPath = artPath), token); return
                }
            // LAME writes a bare stream with zero tags — prepend ID3v2.3 (title/artist/cover) so the
            // file is identifiable anywhere. Best-effort: a tagging failure must not lose the audio.
            runCatching { Id3.embed(part, start.title, start.uploader, artPath?.let { File(it) }) }
                .onFailure { e -> Log.w("PULSE", "id3 embed failed", e) }
            val mp3 = File(dir, "$stem.mp3")
            if (!part.renameTo(mp3)) { part.delete(); fail(start.copy(artPath = artPath), token); return }
            complete(context, start, token, mp3, artPath)
        } finally {
            source.delete()
        }
    }

    /**
     * The stream URL for [start]'s format, or null. Each format gets the container its name promises: the playback
     * pick (audioUrl) is usually WebM/Opus, which other players reject as .m4a or .mp4 — so M4A insists on
     * AAC-in-MP4, and MP4 without muxed video falls back to that audio-only MP4, never to WebM.
     */
    private fun resolveStream(start: DownloadItem): String? {
        val t0 = System.nanoTime()
        val data = runCatching { StreamResolver.resolve(start.url) }.getOrNull()
        Log.i("PULSE/DL", "resolved ${start.id} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        // The fast iOS answer can lack a format that NewPipe's full extraction lists — only then pay for one.
        val full by lazy { runCatching { Extractor.streamInfo(start.url) }.getOrNull() }
        return when (start.format) {
            DlFormat.MP4 -> data?.videoUrl ?: full?.videoUrl ?: data?.m4aUrl ?: full?.m4aUrl
            DlFormat.M4A -> data?.m4aUrl ?: full?.m4aUrl
            // AAC is the MP3 source whenever there is one: on the user's phone MediaCodec decoded AAC about twice as fast
            // per second of audio as Opus (fewer, cheaper frames) — and decoding is most of an MP3 download's time.
            DlFormat.MP3 -> data?.m4aUrl ?: data?.audioUrl
        }
    }

    /** Runs [block] holding one of the user's simultaneous-download slots, waiting (still shown Queued) for one to free up. */
    private suspend fun <T> withFetchSlot(block: suspend () -> T): T {
        turnstile.withLock {
            // Wakes when a slot frees up, the limit is raised, or Wi-Fi arrives / the Wi-Fi-only switch is turned off.
            combine(activeFetches, settings, onWifi) { active, s, wifi ->
                active < s.maxConcurrent.coerceAtLeast(1) && (!s.wifiOnly || wifi)
            }.first { it }
            activeFetches.update { it + 1 }
        }
        try {
            return block()
        } finally {
            activeFetches.update { it - 1 }
        }
    }

    /**
     * Publishes the finished [local] file to Download/Emma and records it there. When that isn't possible
     * (pre-Android-10 without the storage permission) the private copy is recorded instead, and
     * [migrateToPublic] moves it once the permission arrives. A removed attempt cleans up after itself.
     */
    private fun complete(context: Context, start: DownloadItem, token: Any, local: File, artPath: String?) {
        fun discard(published: String?) {
            local.delete()
            artPath?.let { File(it).delete() }
            published?.let { PublicStore.delete(context, it) }
        }
        if (live[start.id] !== token) { discard(null); return }   // removed during art/transcode: don't even copy
        val published = publish(context, local, start)
        val done = start.copy(status = DlStatus.Completed, progress = 1f, filePath = published ?: local.absolutePath, artPath = artPath)
        if (!upsert(done, persist = true, token = token)) { discard(published); return }   // removed during the copy
        if (published != null) local.delete()
    }

    /**
     * Best-effort side-car cover, saved as {stem}.jpg in app storage so the Downloads list,
     * NowPlaying and the media notification can show art with zero network. Tries YouTube's largest
     * still first (maxresdefault 404s for many music uploads; hqdefault always exists) when the
     * video id is known, then whatever thumb the item was enqueued with. Runs inside the per-item
     * download coroutine (so remove() cancels it too) and NEVER throws or affects download status —
     * art is decoration, not payload.
     */
    private suspend fun fetchArt(dir: File, stem: String, item: DownloadItem): String? {
        val art = File(dir, "$stem.jpg")
        val videoId = runCatching { Uri.parse(item.url).getQueryParameter("v") }.getOrNull()
        val candidates = buildList {
            if (videoId != null) {
                add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            }
            item.thumbnailUrl?.let { add(it) }
        }
        for (u in candidates) {
            // A candidate can throw mid-body (network drop leaves a partial file); the next attempt
            // reopens/truncates the same file, so a later success always yields a complete image.
            val ok = runCatching { fetchWhole(u, art) { } }.getOrDefault(false)
            if (ok && art.length() > 0L) return art.absolutePath
        }
        runCatching { art.delete() }   // don't leave a zero-byte / half-written husk behind
        return null
    }

    /**
     * Downloads [url] into [file] as parallel 1 MiB ranged requests. googlevideo throttles each connection
     * to roughly playback speed, so one long stream crawls while several short ranged ones run at line
     * speed (the same approach NewPipe's own downloader takes). Falls back to a single plain stream when
     * the server won't serve ranges. False on an HTTP error; throws on I/O failure.
     */
    private suspend fun fetchMedia(url: String, file: File, onProgress: (Float) -> Unit): Boolean {
        val startNs = System.nanoTime()
        val total = probeSize(url) ?: run {
            Log.i("PULSE/DL", "server refused ranges — single-stream download")
            return fetchWhole(url, file, onProgress)
        }
        val chunkCount = (total + CHUNK_BYTES - 1) / CHUNK_BYTES
        val next = AtomicLong(0)
        val progress = Progress(total, onProgress)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(total)   // a retry reuses the name — never inherit a previous attempt's tail
            val channel = raf.channel
            coroutineScope {
                repeat(minOf(WORKERS.toLong(), chunkCount).toInt()) {
                    launch {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= chunkCount) break
                            val from = i * CHUNK_BYTES
                            fetchChunk(url, from, minOf(from + CHUNK_BYTES, total) - 1, channel, progress)
                        }
                    }
                }
            }
        }
        Log.i("PULSE/DL", "fetched ${total / 1024} KiB in $chunkCount chunks: ${(System.nanoTime() - startNs) / 1_000_000} ms")
        return true
    }

    /** Total size from a 1-byte ranged probe; null when the server won't serve ranges or hides the size. */
    private fun probeSize(url: String): Long? =
        client.newCall(ranged(url, 0L, 0L)).execute().use { resp ->
            if (resp.code != 206) null
            else resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0L }
        }

    /** One ranged request written at its offset. Retried, since a single dropped chunk would otherwise fail the whole file. */
    private suspend fun fetchChunk(url: String, from: Long, to: Long, channel: FileChannel, progress: Progress) {
        var attempt = 0
        while (true) {
            var written = 0L
            try {
                client.newCall(ranged(url, from, to)).execute().use { resp ->
                    if (resp.code != 206) throw IOException("HTTP ${resp.code} for bytes $from-$to")
                    val input = (resp.body ?: throw IOException("empty body for bytes $from-$to")).byteStream()
                    val buf = ByteArray(64 * 1024)
                    var pos = from
                    while (true) {
                        currentCoroutineContext().ensureActive()   // remove() cancels promptly, not after the whole file
                        val n = input.read(buf)
                        if (n == -1) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) pos += channel.write(bb, pos)   // positional: safe across workers
                        written += n
                        progress.add(n.toLong())
                    }
                    if (pos != to + 1) throw IOException("short chunk: ${pos - from} of ${to - from + 1} bytes")
                }
                return
            } catch (e: IOException) {
                progress.add(-written)   // the retry downloads these bytes again
                if (++attempt >= CHUNK_ATTEMPTS) throw e
                delay(500L * attempt)
            }
        }
    }

    private fun ranged(url: String, from: Long, to: Long) = okhttp3.Request.Builder().url(url)
        .header("User-Agent", NewPipeDownloader.USER_AGENT)
        .header("Range", "bytes=$from-$to")
        .build()

    /** Byte counter shared by one file's workers; reports whole-percent steps and never goes backwards. */
    private class Progress(private val total: Long, private val onProgress: (Float) -> Unit) {
        private val done = AtomicLong(0)
        private var lastPct = -1
        fun add(bytes: Long) {
            val pct = (done.addAndGet(bytes).coerceIn(0L, total) * 100 / total).toInt()
            synchronized(this) { if (pct > lastPct) { lastPct = pct; onProgress(pct / 100f) } }
        }
    }

    /** Streams [url] to [file] in one request, reporting fractional progress (0f..1f) when the content length is known. Returns false on an HTTP error. */
    private suspend fun fetchWhole(url: String, file: File, onProgress: (Float) -> Unit): Boolean {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", NewPipeDownloader.USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false   // an expired/403 googlevideo URL still has a body — don't save the error page
            val body = resp.body ?: return false
            val total = body.contentLength()
            var lastPct = -1
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        currentCoroutineContext().ensureActive()   // cancellable: remove() mustn't wait out a whole body
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * Keeps [onWifi] current. One callback for the process's lifetime (init() runs once). On Android 9+ the default
     * network's capabilities are pushed to us — unlike activeNetwork, which reads null while Doze/Battery Saver blocks
     * the app, so a pulled value could stick at "not Wi-Fi" after the block lifts. Older versions re-read on each change.
     */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        refreshNetwork()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _onWifi.value = isWifi(caps) }
                    override fun onLost(network: Network) { _onWifi.value = false }
                })
            } else {
                val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refreshNetwork()
                    override fun onLost(network: Network) = refreshNetwork()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshNetwork()
                })
            }
        }.onFailure { e -> Log.w("PULSE/DL", "network watch unavailable", e) }
    }

    /** Re-reads the current network — at start, on older Androids' callbacks, and whenever Emma comes to the front. */
    fun refreshNetwork() {
        val cm = connectivity ?: return
        // Some Android 11 builds throw SecurityException from getNetworkCapabilities (b/163342798): keep the last value.
        runCatching { isOnWifi(cm) }
            .onSuccess { _onWifi.value = it }
            .onFailure { e -> Log.w("PULSE/DL", "network check failed", e) }
    }

    private fun isWifi(caps: NetworkCapabilities) =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    // Below Android 9 a VPN's capabilities don't carry its underlying transport, so there the legacy activeNetworkInfo
    // — which reports the VPN's underlying network — is the accurate read.
    @Suppress("DEPRECATION")
    private fun isOnWifi(cm: ConnectivityManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.getNetworkCapabilities(cm.activeNetwork)?.let { isWifi(it) } == true
        } else {
            cm.activeNetworkInfo?.type.let { it == ConnectivityManager.TYPE_WIFI || it == ConnectivityManager.TYPE_ETHERNET }
        }

    /** Publishes [local] to wherever Settings says downloads go, named after [item]'s title. */
    private fun publish(context: Context, local: File, item: DownloadItem, s: AppSettings = settings.value, ext: String = item.format.ext): String? =
        PublicStore.publish(
            context, local, PublicStore.fileName(item.title, ext),
            s.downloadTreeUri, s.downloadRelPath ?: PublicStore.DEFAULT_REL_PATH,
        )

    /** WebM's EBML magic (1A 45 DF A3) at the start of [f]. */
    private fun isWebm(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        }
    }.getOrDefault(false)

    /**
     * A picked folder whose grant is gone — restored from a backup (grants never are) or revoked — can't be written.
     * Fall back to the preset rather than saving elsewhere while Settings still names that folder. A grant stays
     * listed while its SD card is out, so a temporarily missing card doesn't reset the choice.
     */
    private suspend fun forgetUnusableFolder(s: AppSettings): AppSettings {
        val tree = s.downloadTreeUri ?: return s
        val held = appContext.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }
        if (held) return s
        Log.i("PULSE/DL", "download folder access is gone — back to ${s.downloadRelPath ?: PublicStore.DEFAULT_REL_PATH}")
        SettingsStore.setDownloadFolder(appContext, null, null)
        return s.copy(downloadTreeUri = null, downloadFolderLabel = null)
    }

    private fun privateDir(context: Context) = File(context.getExternalFilesDir(null), "downloads")

    /**
     * Marks the attempt Failed and drops its cached stream URL, so Retry resolves afresh instead of reusing a URL
     * that just died (googlevideo URLs are IP-bound: a Wi-Fi → mobile switch kills them). A stale attempt's write
     * is dropped, so it deletes the cover it would otherwise strand.
     */
    private fun fail(rec: DownloadItem, token: Any) {
        StreamResolver.evict(rec.url)
        if (!upsert(rec.copy(status = DlStatus.Failed), persist = true, token = token)) rec.artPath?.let { File(it).delete() }
    }

    /** Writes an attempt's record; false (and no write) once that attempt is stale — removed, or replaced by a retry. */
    private fun upsert(rec: DownloadItem, persist: Boolean, token: Any): Boolean {
        var written = false
        _items.update { list ->
            // Checked inside the update so it serializes with remove()'s own update — no zombie slips in between.
            written = live[rec.id] === token
            when {
                !written -> list
                list.any { it.id == rec.id } -> list.map { if (it.id == rec.id) rec else it }
                else -> list + rec
            }
        }
        if (written && persist) persist()
        return written
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(it.toJson()) }
        // Write-then-rename: a failed or interrupted write (disk full, process killed) leaves the previous file whole
        // instead of a truncated one that loses every record. rename(2) replaces the old file in one step.
        runCatching {
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(arr.toString())
            check(tmp.renameTo(storeFile)) { "rename failed" }
        }.onFailure { e -> android.util.Log.e("PULSE/DL", "persist failed", e) }
    }

    private fun load(): List<DownloadItem> {
        if (!storeFile.exists()) return emptyList()
        val arr = JSONArray(storeFile.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it).toItem() }
            // Cut off mid-transfer: Failed, for a manual retry. Still queued: stays Queued, and init() resumes it.
            .map { if (it.status == DlStatus.Downloading) it.copy(status = DlStatus.Failed) else it }
    }
}

private fun DownloadItem.toJson() = JSONObject().apply {
    put("id", id); put("url", url); put("title", title); put("uploader", uploader)
    put("thumb", thumbnailUrl ?: JSONObject.NULL); put("format", format.name); put("status", status.name)
    put("progress", progress.toDouble()); put("file", filePath ?: JSONObject.NULL)
    put("art", artPath ?: JSONObject.NULL)
}

private fun JSONObject.toItem() = DownloadItem(
    id = getString("id"), url = getString("url"), title = getString("title"), uploader = getString("uploader"),
    thumbnailUrl = if (isNull("thumb")) null else getString("thumb"),
    format = DlFormat.valueOf(getString("format")), status = DlStatus.valueOf(getString("status")),
    progress = getDouble("progress").toFloat(), filePath = if (isNull("file")) null else getString("file"),
    // isNull() is true for a MISSING key too, so records persisted before art existed still load.
    artPath = if (isNull("art")) null else getString("art"),
)
