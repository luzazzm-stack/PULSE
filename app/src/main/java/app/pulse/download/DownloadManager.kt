package app.pulse.download

import android.content.Context
import android.net.Uri
import android.util.Log
import app.pulse.core.NewPipeDownloader
import app.pulse.core.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Downloads YouTube streams (MP4 max / M4A audio / MP3 transcoded) to app storage with progress; persists across launches. */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    // AndroidLame wraps a single global native encoder, so overlapping transcodes corrupt each other — serialize them.
    private val transcodeMutex = Mutex()
    // Per-id download coroutine, so remove() can cancel an in-flight download; and a set of ids the user removed,
    // so a still-running download's late upsert can't resurrect it as a "zombie" Completed entry.
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelledIds = Collections.synchronizedSet(HashSet<String>())
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private var loaded = false
    private lateinit var storeFile: File

    fun init(context: Context) {
        if (loaded) return
        storeFile = File(context.filesDir, "downloads.json")
        _items.value = runCatching { load() }.getOrDefault(emptyList())
        loaded = true
    }

    fun enqueue(context: Context, url: String, title: String, uploader: String, thumbnailUrl: String?, format: DlFormat) {
        val id = (Uri.parse(url).getQueryParameter("v") ?: url.hashCode().toString()) + "_" + format.ext
        val existing = _items.value.firstOrNull { it.id == id }
        if (existing != null && existing.status != DlStatus.Failed) return
        cancelledIds.remove(id)   // re-enqueue (e.g. retry) clears any prior removal
        val rec = DownloadItem(id, url, title, uploader, thumbnailUrl, format, DlStatus.Queued)
        upsert(rec, persist = true)
        val app = context.applicationContext
        jobs[id] = scope.launch {
            try {
                runCatching { download(app, rec) }.onFailure { e -> Log.e("PULSE", "download failed", e); fail(rec) }
            } finally {
                jobs.remove(id)
            }
        }
    }

    fun remove(item: DownloadItem) {
        cancelledIds.add(item.id)          // block any late upsert from the still-running coroutine
        jobs.remove(item.id)?.cancel()     // stop wasting network/CPU on a download the user removed
        item.filePath?.let { runCatching { File(it).delete() } }
        item.artPath?.let { runCatching { File(it).delete() } }   // the side-car cover goes with the media
        _items.update { list -> list.filterNot { it.id == item.id } }
        persist()
    }

    private suspend fun download(context: Context, start: DownloadItem) {
        upsert(start.copy(status = DlStatus.Downloading, progress = 0f), persist = true)
        // Shared cached resolver: "download the song I'm playing" reuses playback's resolution instead
        // of paying a second full extraction.
        val data = runCatching { StreamResolver.resolve(start.url) }.getOrNull()
        val streamUrl = if (start.format == DlFormat.MP4) (data?.videoUrl ?: data?.audioUrl) else data?.audioUrl
        if (streamUrl == null) { fail(start); return }

        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

        if (start.format == DlFormat.MP3) {
            // YouTube never serves MP3 — download the AAC/M4A audio to a temp file, then transcode on-device.
            // Downloading fills ~85% of the bar; the transcode fills the rest. The temp is always removed.
            val tmp = File(dir, "${start.id}.src.tmp")
            try {
                val fetched = fetchToFile(streamUrl, tmp) { p -> upsert(start.copy(status = DlStatus.Downloading, progress = p * 0.85f), persist = false) }
                if (!fetched) { fail(start); return }
                // Grab the cover BEFORE the transcode so the finished MP3 can carry it embedded.
                val artPath = fetchArt(dir, start)
                upsert(start.copy(status = DlStatus.Downloading, progress = 0.9f), persist = false)
                val mp3 = File(dir, "${start.id}.mp3")
                runCatching { transcodeMutex.withLock { Mp3Transcoder.transcode(tmp, mp3) } }
                    // Carry the already-fetched cover into the Failed record: {id}.jpg is on disk by
                    // now, and remove() deletes only what the record references — a bare fail(start)
                    // (artPath = null) would strand the jpg in external storage forever.
                    .onFailure { e -> Log.e("PULSE", "mp3 transcode failed", e); mp3.delete(); fail(start.copy(artPath = artPath)); return }
                // LAME writes a bare stream with zero tags — prepend ID3v2.3 (title/artist/cover) so the
                // file is identifiable anywhere. Best-effort: a tagging failure must not lose the audio.
                runCatching { Id3.embed(mp3, start.title, start.uploader, artPath?.let { File(it) }) }
                    .onFailure { e -> Log.w("PULSE", "id3 embed failed", e) }
                upsert(start.copy(status = DlStatus.Completed, progress = 1f, filePath = mp3.absolutePath, artPath = artPath), persist = true)
            } finally {
                tmp.delete()
            }
            return
        }

        val file = File(dir, "${start.id}.${start.format.ext}")
        val fetched = fetchToFile(streamUrl, file) { p -> upsert(start.copy(status = DlStatus.Downloading, progress = p), persist = false) }
        if (!fetched) { fail(start); return }
        val artPath = fetchArt(dir, start)
        upsert(start.copy(status = DlStatus.Completed, progress = 1f, filePath = file.absolutePath, artPath = artPath), persist = true)
    }

    /**
     * Best-effort side-car cover, saved as {id}.jpg next to the media file so the Downloads list,
     * NowPlaying and the media notification can show art with zero network. Tries YouTube's largest
     * still first (maxresdefault 404s for many music uploads; hqdefault always exists) when the
     * video id is known, then whatever thumb the item was enqueued with. Runs inside the per-item
     * download coroutine (so remove() cancels it too) and NEVER throws or affects download status —
     * art is decoration, not payload.
     */
    private fun fetchArt(dir: File, item: DownloadItem): String? {
        val art = File(dir, "${item.id}.jpg")
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
            val ok = runCatching { fetchToFile(u, art) { } }.getOrDefault(false)
            if (ok && art.length() > 0L) return art.absolutePath
        }
        runCatching { art.delete() }   // don't leave a zero-byte / half-written husk behind
        return null
    }

    /** Streams [url] to [file], reporting fractional progress (0f..1f) when the content length is known. Returns false on a null body. */
    private fun fetchToFile(url: String, file: File, onProgress: (Float) -> Unit): Boolean {
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

    private fun fail(rec: DownloadItem) = upsert(rec.copy(status = DlStatus.Failed), persist = true)

    private fun upsert(rec: DownloadItem, persist: Boolean) {
        if (cancelledIds.contains(rec.id)) return   // user removed this download — don't let a late write resurrect it
        _items.update { list ->
            if (list.any { it.id == rec.id }) list.map { if (it.id == rec.id) rec else it } else list + rec
        }
        if (persist) persist()
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(it.toJson()) }
        runCatching { storeFile.writeText(arr.toString()) }
            .onFailure { e -> android.util.Log.e("PULSE/DL", "persist failed", e) }
    }

    private fun load(): List<DownloadItem> {
        if (!storeFile.exists()) return emptyList()
        val arr = JSONArray(storeFile.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it).toItem() }
            .map { if (it.status == DlStatus.Downloading || it.status == DlStatus.Queued) it.copy(status = DlStatus.Failed) else it }
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
