package app.pulse.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stream-URL resolution with a fast path and a cache — the answer to "why does Next take seconds".
 *
 * Fast path: one direct InnerTube /player call as the iOS client, which YouTube answers with plain
 * (non-ciphered) stream URLs — a single round-trip instead of NewPipe's page fetch + player fetch +
 * JS cipher work. Anything short of a fully usable answer falls back to the proven NewPipe path in
 * [Extractor.streamInfo], so worst case is exactly as good as before.
 *
 * Cache: small LRU keyed by watch URL. Googlevideo URLs live ~6h; entries are trusted for 45min,
 * which is short enough that an expired-in-cache URL is rare and long enough that skip-back-and-forth,
 * prefetch, and "download the song I'm playing" all reuse one resolution. Call off the main thread.
 */
object StreamResolver {

    private const val MAX_ENTRIES = 30
    private const val TTL_MS = 45 * 60 * 1000L

    // The iOS client gets direct URLs; keep its whole fingerprint consistent (client JSON + UA).
    private const val IOS_UA = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    // Short timeouts on purpose: when this path is going to fail, fail fast so the NewPipe fallback
    // starts while the user is still watching the loading state — not after a 30s socket stall.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private class Entry(val data: StreamData, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }

    /**
     * Resolve a watch URL to stream URLs: fresh cache hit → instant; else fast iOS path → NewPipe
     * fallback. Throws only when BOTH paths fail (same contract callers already handle for
     * [Extractor.streamInfo]); a success is always cached.
     */
    fun resolve(url: String): StreamData {
        synchronized(cache) {
            val hit = cache[url]
            if (hit != null) {
                if (System.currentTimeMillis() - hit.at < TTL_MS) return hit.data
                cache.remove(url)
            }
        }
        val data = fastResolve(url) ?: Extractor.streamInfo(url)
        synchronized(cache) { cache[url] = Entry(data, System.currentTimeMillis()) }
        return data
    }

    /** True when googlevideo actually serves [streamUrl]: a 2-byte ranged GET. The iOS-client URLs are
     *  intermittently refused with 403 (about half of the queued tracks on the test phone), which used to
     *  surface only as a few seconds of silence when the player reached them. */
    fun isServed(streamUrl: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(streamUrl).header("Range", "bytes=0-1").build()).execute().use { it.isSuccessful }
    }.getOrDefault(true)   // network hiccup: don't churn the resolver, let the player's own retry handle it

    /** Drop a cached entry — used after a player error, when the cached URL is exactly what died. */
    fun evict(url: String) {
        synchronized(cache) { cache.remove(url) }
    }

    /** One /player call as the iOS client. Null on ANY shortfall — caller falls back to NewPipe. */
    private fun fastResolve(url: String): StreamData? = runCatching {
        val videoId = videoIdOf(url) ?: return null
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "19.45.4")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iOS")
                put("osVersion", "18.1.0.22B83")
                put("hl", "en"); put("gl", "US")
            }))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val req = Request.Builder()
            .url(PLAYER_ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", IOS_UA)
            .build()
        val root = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string() ?: return null)
        }
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null

        // Best direct audio stream. No URL field means ciphered — this client shouldn't produce those,
        // but if it does, bail to NewPipe rather than guess.
        var audioUrl: String? = null; var audioScore = -1
        var m4aUrl: String? = null; var m4aScore = -1
        val adaptive = streaming.optJSONArray("adaptiveFormats")
        for (i in 0 until (adaptive?.length() ?: 0)) {
            val f = adaptive!!.optJSONObject(i) ?: continue
            val mimeType = f.optString("mimeType")
            if (!mimeType.startsWith("audio/")) continue
            val u = f.optString("url"); if (u.isBlank()) continue
            val score = f.optInt("averageBitrate", f.optInt("bitrate", 0))
            if (score > audioScore) { audioScore = score; audioUrl = u }
            if (mimeType.startsWith("audio/mp4") && score > m4aScore) { m4aScore = score; m4aUrl = u }
        }
        // Playback (and every download format) needs audio; a fast answer without it is no answer.
        if (audioUrl == null) return null

        // Best muxed video (progressive MP4), for video mode / MP4 downloads.
        var videoUrl: String? = null; var videoScore = -1
        val muxed = streaming.optJSONArray("formats")
        for (i in 0 until (muxed?.length() ?: 0)) {
            val f = muxed!!.optJSONObject(i) ?: continue
            val u = f.optString("url"); if (u.isBlank()) continue
            val score = f.optInt("height", 0)
            if (score > videoScore) { videoScore = score; videoUrl = u }
        }

        val details = root.optJSONObject("videoDetails")
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url")?.takeIf { it.isNotBlank() }
        StreamData(
            url = url,
            title = details?.optString("title").orEmpty(),
            uploader = details?.optString("author").orEmpty(),
            durationMs = (details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000,
            thumbnailUrl = thumb,
            audioUrl = audioUrl,
            videoUrl = videoUrl,
            m4aUrl = m4aUrl,
        )
    }.getOrNull()

    private fun videoIdOf(url: String): String? =
        url.substringAfter("v=", "").takeWhile { it != '&' }.takeIf { it.isNotBlank() }
}
