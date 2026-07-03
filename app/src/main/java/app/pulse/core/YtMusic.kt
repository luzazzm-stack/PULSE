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

/** Fetches YouTube Music's anonymous home feed via the InnerTube (youtubei) API. Call off the main thread. */
object YtMusic {

    private val client = OkHttpClient()
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250601.01.00"
    private val JSON = "application/json".toMediaType()

    fun home(): List<HomeShelf> {
        val root = fetch("FEmusic_home")
        val sections = root
            .o("contents")?.o("singleColumnBrowseResultsRenderer")
            ?.a("tabs")?.obj(0)?.o("tabRenderer")?.o("content")
            ?.o("sectionListRenderer")?.a("contents")
            ?: return emptyList()

        val shelves = ArrayList<HomeShelf>()
        for (i in 0 until sections.length()) {
            val sec = sections.obj(i) ?: continue
            val carousel = sec.o("musicCarouselShelfRenderer") ?: sec.o("musicImmersiveCarouselShelfRenderer") ?: continue
            val title = carousel.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")
                ?.o("title")?.a("runs")?.obj(0)?.s("text") ?: "More"
            val contents = carousel.a("contents") ?: continue
            val cards = ArrayList<HomeCard>()
            for (j in 0 until contents.length()) {
                parseCard(contents.obj(j) ?: continue)?.let { cards.add(it) }
            }
            if (cards.isNotEmpty()) shelves.add(HomeShelf(title, cards))
        }
        return shelves
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

    private fun fetch(browseId: String): JSONObject {
        val body = JSONObject().apply {
            put("browseId", browseId)
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
                put("gl", "US")
            }))
        }
        val req = okhttp3.Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?key=$KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            return JSONObject(resp.body?.string() ?: "{}")
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
            ?: o("thumbnail")?.o("thumbnails")?.a("thumbnails")
        return thumbs?.obj(thumbs.length() - 1)?.s("url")
    }
}
