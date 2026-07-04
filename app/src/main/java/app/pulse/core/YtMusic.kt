package app.pulse.core

import androidx.compose.runtime.Immutable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class HomeCard(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
) {
    val playable: Boolean get() = videoId != null
    fun toStreamItem(): StreamItem =
        StreamItem("https://www.youtube.com/watch?v=$videoId", title, subtitle, 0, thumbnailUrl)
}

@Immutable
data class HomeShelf(val title: String, val cards: List<HomeCard>)

@Immutable
data class BrowseResult(val title: String, val subtitle: String, val thumbnailUrl: String?, val tracks: List<StreamItem>)

/** Fetches YouTube Music's anonymous home feed via the InnerTube (youtubei) API. Call off the main thread. */
object YtMusic {

    private val client = OkHttpClient()
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250601.01.00"
    private val JSON = "application/json".toMediaType()

    fun home(): List<HomeShelf> {
        // Personalized home when signed in; anonymous otherwise. Walk one continuation for depth, and for
        // anonymous/thin feeds merge curated New Releases + Charts so the home is never a near-empty wall.
        val root = fetch("FEmusic_home", auth = AuthStore.connected)
        val shelves = ArrayList<HomeShelf>()
        collectShelves(root, shelves)
        continuationToken(root)?.let { runCatching { collectShelves(fetchContinuation(it, AuthStore.connected), shelves) } }
        if (!AuthStore.connected || shelves.size < 5) {
            for (feed in listOf("FEmusic_new_releases", "FEmusic_charts")) {
                runCatching { collectShelves(fetch(feed, auth = false), shelves) }
            }
        }
        return shelves.distinctBy { it.title }.filter { it.cards.isNotEmpty() }
    }

    /** Recursively collect every carousel/grid shelf in any browse response (home, charts, new_releases…). */
    private fun collectShelves(node: Any?, out: MutableList<HomeShelf>) {
        when (node) {
            is JSONObject -> {
                val carousel = node.o("musicCarouselShelfRenderer") ?: node.o("musicImmersiveCarouselShelfRenderer")
                val grid = node.o("gridRenderer")
                when {
                    carousel != null -> addShelf(
                        carousel.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")?.o("title")?.a("runs")?.obj(0)?.s("text"),
                        carousel.a("contents"), out)
                    grid != null -> addShelf(
                        grid.o("header")?.o("gridHeaderRenderer")?.o("title")?.a("runs")?.obj(0)?.s("text"),
                        grid.a("items"), out)
                    else -> { val keys = node.keys(); while (keys.hasNext()) collectShelves(node.opt(keys.next()), out) }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectShelves(node.opt(i), out)
        }
    }

    private fun addShelf(title: String?, contents: JSONArray?, out: MutableList<HomeShelf>) {
        if (contents == null) return
        val cards = ArrayList<HomeCard>()
        for (j in 0 until contents.length()) parseCard(contents.obj(j) ?: continue)?.let { cards.add(it) }
        if (cards.isNotEmpty()) out.add(HomeShelf(title ?: "More", cards))
    }

    private fun continuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.o("nextContinuationData")?.s("continuation")?.let { return it }
                node.o("reloadContinuationData")?.s("continuation")?.let { return it }
                node.o("continuationCommand")?.s("token")?.let { return it }
                val keys = node.keys(); while (keys.hasNext()) continuationToken(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) continuationToken(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun fetchContinuation(token: String, auth: Boolean): JSONObject =
        post("browse", JSONObject().apply { put("continuation", token); put("context", contextClient()) }, auth)

    /** Search via InnerTube — the query goes in the JSON body, so it avoids NewPipe's
     *  URLEncoder.encode(String, Charset) call (an API-33 method that crashes on Android < 13). */
    fun search(query: String, videos: Boolean = false): List<StreamItem> {
        val body = JSONObject().apply {
            put("query", query)
            put("params", if (videos) "EgWKAQIQAWoMEAMQBBAJEAoQBRAV" else "EgWKAQIIAWoMEAMQBBAJEAoQBRAV")
            put("context", contextClient())
        }
        val tracks = ArrayList<StreamItem>()
        collectTracks(post("search", body, auth = false), tracks)
        // Fallback: if the filtered search returned nothing (params rejected), try a plain search.
        if (tracks.isEmpty()) {
            collectTracks(post("search", JSONObject().apply { put("query", query); put("context", contextClient()) }, auth = false), tracks)
        }
        return tracks.distinctBy { it.url }
    }

    private fun parseCard(item: JSONObject): HomeCard? {
        item.o("musicTwoRowItemRenderer")?.let { r ->
            val title = r.o("title")?.a("runs")?.obj(0)?.s("text") ?: return null
            val subtitle = r.o("subtitle")?.a("runs").joinRuns()
            val nav = r.o("navigationEndpoint")
            val videoId = nav?.o("watchEndpoint")?.s("videoId")
            val browseId = nav?.o("browseEndpoint")?.s("browseId")
            return HomeCard(title, subtitle, r.thumbUrl(), videoId, browseId)
        }
        item.o("musicResponsiveListItemRenderer")?.let { r ->
            val flex = r.a("flexColumns")
            val col0 = flex?.obj(0)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text")
            val title = col0?.a("runs")?.obj(0)?.s("text") ?: return null
            val subtitle = flex?.obj(1)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text")?.a("runs").joinRuns()
            val videoId = r.o("playlistItemData")?.s("videoId")
                ?: col0?.a("runs")?.obj(0)?.o("navigationEndpoint")?.o("watchEndpoint")?.s("videoId")
            return HomeCard(title, subtitle, r.thumbUrl(), videoId, null)
        }
        return null
    }

    /** The user's YouTube "Liked Music" playlist — needs a connected account. */
    const val LIKED_BROWSE_ID = "FEmusic_liked_videos"

    /** Like / un-like a track on the user's YouTube account. Call off the main thread. */
    fun rate(videoId: String, liked: Boolean) {
        val body = JSONObject().apply {
            put("target", JSONObject().put("videoId", videoId))
            put("context", contextClient())
        }
        post(if (liked) "like/like" else "like/removelike", body, auth = true)
    }

    /** Fetch an album / playlist / artist page: header + all playable tracks found in the response.
     *  Private feeds (FEmusic_*, e.g. Liked Music) are signed with the session cookie. */
    fun browse(browseId: String): BrowseResult {
        val root = fetch(browseId, auth = browseId.startsWith("FEmusic_"))
        val tracks = ArrayList<StreamItem>()
        collectTracks(root, tracks)
        val header = listOf(
            "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer",
            "musicImmersiveHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
        ).firstNotNullOfOrNull { findFirst(root, it) }
        val title = header?.o("title")?.a("runs")?.obj(0)?.s("text")
            ?: if (browseId == LIKED_BROWSE_ID) "Liked Music" else "Playlist"
        val subtitle = header?.o("subtitle")?.a("runs").joinRuns()
        val thumb = header?.thumbUrl() ?: tracks.firstOrNull()?.thumbnailUrl
        return BrowseResult(title, subtitle, thumb, tracks.distinctBy { it.url })
    }

    private fun collectTracks(node: Any?, out: MutableList<StreamItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { parseTrack(it)?.let { t -> out.add(t) } }
                val keys = node.keys()
                while (keys.hasNext()) collectTracks(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTracks(node.opt(i), out)
        }
    }

    private fun parseTrack(r: JSONObject): StreamItem? {
        val flex = r.a("flexColumns") ?: return null
        val col0 = flex.obj(0)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text")
        val title = col0?.a("runs")?.obj(0)?.s("text") ?: return null
        val artist = flex.obj(1)?.o("musicResponsiveListItemFlexColumnRenderer")?.o("text")?.a("runs").joinRuns()
        val videoId = r.o("playlistItemData")?.s("videoId")
            ?: col0?.a("runs")?.obj(0)?.o("navigationEndpoint")?.o("watchEndpoint")?.s("videoId")
            ?: return null
        val durText = r.a("fixedColumns")?.obj(0)?.o("musicResponsiveListItemFixedColumnRenderer")?.o("text")?.a("runs")?.obj(0)?.s("text")
        return StreamItem("https://www.youtube.com/watch?v=$videoId", title, artist, parseClock(durText), r.thumbUrl())
    }

    private fun parseClock(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.split(":").map { it.trim().toIntOrNull() ?: return 0 }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
            2 -> (parts[0] * 60 + parts[1]).toLong()
            else -> 0
        }
    }

    private fun findFirst(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findFirst(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirst(node.opt(i), key)?.let { return it }
        }
        return null
    }

    /** Fetch lyrics for a video, or null if unavailable. */
    fun lyrics(videoId: String): String? {
        val next = post("next", JSONObject().apply { put("videoId", videoId); put("context", contextClient()) }, auth = false)
        val browseId = findLyricsBrowseId(next) ?: return null
        val root = fetch(browseId, auth = false)
        val shelf = findFirst(root, "musicDescriptionShelfRenderer") ?: return null
        val runs = shelf.o("description")?.a("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) runs.obj(i)?.s("text")?.let { sb.append(it) }
        return sb.toString().ifBlank { null }
    }

    private fun findLyricsBrowseId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("browseEndpoint")?.let { if (!it.isNull("browseId")) { val b = it.optString("browseId"); if (b.startsWith("MPLYt")) return b } }
                val keys = node.keys()
                while (keys.hasNext()) findLyricsBrowseId(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findLyricsBrowseId(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun contextClient(): JSONObject = JSONObject().put("client", JSONObject().apply {
        put("clientName", "WEB_REMIX")
        put("clientVersion", CLIENT_VERSION)
        put("hl", "en")
        put("gl", "US")
    })

    private fun fetch(browseId: String, auth: Boolean = true): JSONObject =
        post("browse", JSONObject().apply { put("browseId", browseId); put("context", contextClient()) }, auth)

    private fun post(endpoint: String, body: JSONObject, auth: Boolean = true): JSONObject {
        val builder = okhttp3.Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=$KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
        // Cookie + SAPISIDHASH — the same auth the music.youtube.com web app sends. (OAuth Bearer
        // tokens stopped working for YT Music endpoints in Nov 2024.)
        if (auth) AuthStore.cookies?.let { c ->
            builder.header("Cookie", c)
            AuthStore.sapisidHash()?.let { builder.header("Authorization", it) }
            builder.header("X-Goog-AuthUser", "0")
        }
        client.newCall(builder.build()).execute().use { resp ->
            // YouTube returns HTML (not JSON) on rate-limit/captcha/5xx — never let that throw.
            val text = resp.body?.string()?.trimStart() ?: "{}"
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    // ---- defensive JSON helpers (never throw) ----
    private fun JSONObject.o(k: String): JSONObject? = optJSONObject(k)
    private fun JSONObject.a(k: String): JSONArray? = optJSONArray(k)
    private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
    private fun JSONArray.obj(i: Int): JSONObject? = optJSONObject(i)

    private fun JSONArray?.joinRuns(): String {
        if (this == null) return ""
        val sb = StringBuilder()
        for (i in 0 until length()) obj(i)?.s("text")?.let { sb.append(it) }
        return sb.toString()
    }

    private fun JSONObject.thumbUrl(): String? {
        val thumbs = o("thumbnailRenderer")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails")
            ?: o("thumbnail")?.o("musicThumbnailRenderer")?.o("thumbnail")?.a("thumbnails")
            ?: o("thumbnail")?.a("thumbnails")
        return thumbs?.obj(thumbs.length() - 1)?.s("url")
    }
}
