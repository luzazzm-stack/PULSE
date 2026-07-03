package app.pulse.download

import android.content.Context
import android.net.Uri
import android.util.Log
import app.pulse.core.Extractor
import app.pulse.core.NewPipeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Downloads YouTube streams (MP4 max / M4A audio) to app storage with progress; persists across launches. */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
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
        val rec = DownloadItem(id, url, title, uploader, thumbnailUrl, format, DlStatus.Queued)
        upsert(rec, persist = true)
        val app = context.applicationContext
        scope.launch {
            runCatching { download(app, rec) }.onFailure { e -> Log.e("PULSE", "download failed", e); fail(rec) }
        }
    }

    fun remove(item: DownloadItem) {
        item.filePath?.let { runCatching { File(it).delete() } }
        _items.update { list -> list.filterNot { it.id == item.id } }
        persist()
    }

    private suspend fun download(context: Context, start: DownloadItem) {
        upsert(start.copy(status = DlStatus.Downloading, progress = 0f), persist = true)
        val data = runCatching { Extractor.streamInfo(start.url) }.getOrNull()
        val streamUrl = if (start.format == DlFormat.MP4) (data?.videoUrl ?: data?.audioUrl) else data?.audioUrl
        if (streamUrl == null) { fail(start); return }

        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val file = File(dir, "${start.id}.${start.format.ext}")
        val req = okhttp3.Request.Builder().url(streamUrl).header("User-Agent", NewPipeDownloader.USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: run { fail(start); return }
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
                            if (pct != lastPct) { lastPct = pct; upsert(start.copy(status = DlStatus.Downloading, progress = pct / 100f), persist = false) }
                        }
                    }
                }
            }
        }
        upsert(start.copy(status = DlStatus.Completed, progress = 1f, filePath = file.absolutePath), persist = true)
    }

    private fun fail(rec: DownloadItem) = upsert(rec.copy(status = DlStatus.Failed), persist = true)

    private fun upsert(rec: DownloadItem, persist: Boolean) {
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
}

private fun JSONObject.toItem() = DownloadItem(
    id = getString("id"), url = getString("url"), title = getString("title"), uploader = getString("uploader"),
    thumbnailUrl = if (isNull("thumb")) null else getString("thumb"),
    format = DlFormat.valueOf(getString("format")), status = DlStatus.valueOf(getString("status")),
    progress = getDouble("progress").toFloat(), filePath = if (isNull("file")) null else getString("file"),
)
