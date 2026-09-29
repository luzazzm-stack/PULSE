package app.pulse.core

import androidx.compose.runtime.Immutable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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

/** One "Moods & genres" category chip. [color] is YT's solid tile accent — an unsigned 32-bit ARGB
 *  packed in a long (as served in solid.leftStripeColor) — or 0 when absent so the UI can fall back
 *  to its own palette. [browseId]/[params] come from the chip's clickCommand.browseEndpoint and feed
 *  categoryPage(); both nullable because YT occasionally serves chips without a click target. */
@Immutable
data class MoodCategory(
    val name: String,
    val color: Long,
    val browseId: String? = null,
    val params: String? = null,
)

/** Fetches YouTube Music's anonymous home feed via the InnerTube (youtubei) API. Call off the main thread. */
object YtMusic {

    private val client = OkHttpClient()
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250601.01.00"
    private val JSON = "application/json".toMediaType()

    fun home(): List<HomeShelf> {
        // Personalized home when signed in; anonymous otherwise.
        val auth = AuthStore.connected
        val hadVisitorData = visitorData != null
        var root = fetch("FEmusic_home", auth)
        // One short-backoff retry on hard failure: YouTube's transient rate-limit/captcha pages are HTML
        // (post() returns null for those) and usually clear within a moment.
        if (root == null) { Thread.sleep(750); root = fetch("FEmusic_home", auth) }
        val shelves = ArrayList<HomeShelf>()
        collectShelves(root, shelves)
        // Cold start: the very FIRST anonymous browse carries no visitor id, and YouTube often answers it
        // with a parseable but empty home. That empty response still assigns responseContext.visitorData
        // (harvested in post()), so one immediate retry WITH it returns the real feed.
        if (!auth && shelves.isEmpty() && root != null && !hadVisitorData && visitorData != null) {
            root = fetch("FEmusic_home", auth = false)
            collectShelves(root, shelves)
        }
        // Dedupe BEFORE judging sparseness — many shelves fall back to the title "More", so a raw count
        // overstates how many the user actually sees after dedup.
        var result = shelves.dedupeShelves()
        // Pad ANY thin home — signed-in included — with curated global feeds: a sparse personalized feed
        // (new account, empty history) otherwise looks broken. The curated shelves land BELOW the
        // personalized ones (collectShelves appends in order) and dedupeShelves() drops repeats.
        // FEmusic_moods_and_genres is deliberately absent: it renders as musicNavigationButtonRenderer
        // category chips, which parseCard() can't turn into playable cards — it would add nothing.
        var fallbacksTried = 0
        var fallbacksHardFailed = 0
        if (result.size < 5) {
            for (feed in listOf("FEmusic_new_releases", "FEmusic_charts")) {
                fallbacksTried++
                val fb = fetch(feed, auth = false)
                if (fb == null) fallbacksHardFailed++ else collectShelves(fb, shelves)
            }
            result = shelves.dedupeShelves()
        }
        // Throw ONLY on total hard failure (main feed AND every fallback unreachable/unparseable) so the
        // ViewModel can tell "offline / rate-limited" from a parseable-but-genuinely-empty feed.
        if (root == null && fallbacksTried > 0 && fallbacksHardFailed == fallbacksTried) {
            throw IOException("YouTube Music unreachable — network error or rate-limited")
        }
        return result
    }

    /** Recursively collect every carousel/grid shelf in any browse response (home, charts, new_releases…). */
    private fun collectShelves(node: Any?, out: MutableList<HomeShelf>) {
        when (node) {
            is JSONObject -> {
                val carousel = node.o("musicCarouselShelfRenderer") ?: node.o("musicImmersiveCarouselShelfRenderer")
                val grid = node.o("gridRenderer")
                // musicShelfRenderer is the LIST-style shelf some home/browse layouts use (rows of
                // musicResponsiveListItemRenderer, which parseCard already understands) — without it those
                // sections silently vanish from home.
                val list = node.o("musicShelfRenderer")
                when {
                    carousel != null -> addShelf(
                        carousel.o("header")?.o("musicCarouselShelfBasicHeaderRenderer")?.o("title")?.a("runs")?.obj(0)?.s("text"),
                        carousel.a("contents"), out)
                    grid != null -> addShelf(
                        grid.o("header")?.o("gridHeaderRenderer")?.o("title")?.a("runs")?.obj(0)?.s("text"),
                        grid.a("items"), out)
                    list != null -> addShelf(
                        list.o("title")?.a("runs")?.obj(0)?.s("text"),
                        list.a("contents"), out)
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

    /** Distinct shelves keyed by title + first card, so headerless "More" shelves with different content survive
     *  (a plain distinctBy{title} collapses every untitled carousel/grid — and same-titled feeds — into one). */
    private fun List<HomeShelf>.dedupeShelves(): List<HomeShelf> =
        filter { it.cards.isNotEmpty() }
            .distinctBy { it.title + "|" + (it.cards.first().videoId ?: it.cards.first().browseId ?: it.cards.first().title) }

    /** Search via InnerTube — the query goes in the JSON body, so it avoids NewPipe's
     *  URLEncoder.encode(String, Charset) call (an API-33 method that crashes on Android < 13). */
    fun search(query: String, videos: Boolean = false): List<StreamItem> {
        val body = JSONObject().apply {
            put("query", query)
            put("params", if (videos) "EgWKAQIQAWoMEAMQBBAJEAoQBRAV" else "EgWKAQIIAWoMEAMQBBAJEAoQBRAV")
            put("context", contextClient())
        }
        val tracks = ArrayList<StreamItem>()
        val filtered = post("search", body, auth = false)
        collectTracks(filtered, tracks)
        // Fallback: if the filtered search returned nothing (params rejected), try a plain search.
        var plain: JSONObject? = null
        if (tracks.isEmpty()) {
            plain = post("search", JSONObject().apply { put("query", query); put("context", contextClient()) }, auth = false)
            collectTracks(plain, tracks)
        }
        // Both attempts hard-failed (offline / rate-limit HTML) — throw like the pre-null-post OkHttp
        // IOException used to, so the search UI shows its error state instead of a misleading "no results".
        if (filtered == null && plain == null) throw IOException("Search unreachable — network error or rate-limited")
        return tracks.distinctBy { it.url }
    }

    /** Type-ahead suggestions for the search box via music/get_search_suggestions (anonymous — the
     *  query rides in the JSON body like search()). NEVER throws: suggestions are decoration, not
     *  content, so any failure (offline, rate-limit HTML, shape change) just yields an empty list. */
    fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val body = JSONObject().apply {
            put("input", query)
            put("context", contextClient())
        }
        val root = post("music/get_search_suggestions", body, auth = false) ?: return emptyList()
        val out = LinkedHashSet<String>()   // insertion order preserves YT's ranking; set dedupes for free
        collectSuggestions(root, out)
        return out.take(8)
    }

    private fun collectSuggestions(node: Any?, out: MutableCollection<String>) {
        when (node) {
            is JSONObject -> {
                node.o("searchSuggestionRenderer")?.let { r ->
                    // The suggestion text arrives split across runs (the typed prefix is a separate
                    // bolded run) — join them back into the full phrase.
                    val text = r.o("suggestion")?.a("runs").joinRuns()
                    if (text.isNotBlank()) out.add(text)
                }
                val keys = node.keys()
                while (keys.hasNext()) collectSuggestions(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectSuggestions(node.opt(i), out)
        }
    }

    /** The "Moods & genres" category chips (FEmusic_moods_and_genres, anonymous). These render as
     *  musicNavigationButtonRenderer nodes — the one browse shape parseCard() deliberately ignores on
     *  home — so they get their own walker. NEVER throws; empty list on any failure. */
    fun moodsAndGenres(): List<MoodCategory> {
        val root = fetch("FEmusic_moods_and_genres", auth = false) ?: return emptyList()
        val out = ArrayList<MoodCategory>()
        collectMoodButtons(root, out)
        return out.distinctBy { it.name.lowercase() }
    }

    private fun collectMoodButtons(node: Any?, out: MutableList<MoodCategory>) {
        when (node) {
            is JSONObject -> {
                node.o("musicNavigationButtonRenderer")?.let { r ->
                    // solid.leftStripeColor is the tile accent YT Music itself paints; optLong's 0
                    // default doubles as the "no color served" marker for the UI's palette fallback.
                    val name = r.o("buttonText")?.a("runs")?.obj(0)?.s("text")
                    if (!name.isNullOrBlank()) {
                        // The chip's click target — browseId + params reproduce YT Music's own curated
                        // page for this category via categoryPage(); nullable-defensive like the rest.
                        val endpoint = r.o("clickCommand")?.o("browseEndpoint")
                        out.add(MoodCategory(
                            name,
                            r.o("solid")?.optLong("leftStripeColor", 0L) ?: 0L,
                            endpoint?.s("browseId"),
                            endpoint?.s("params"),
                        ))
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectMoodButtons(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectMoodButtons(node.opt(i), out)
        }
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

    /** The user's YouTube "Liked Music" playlist — needs a connected account. VLLM is the playlist itself (the
     *  same id Home's "Liked Music" card opens); the FEmusic_liked_videos feed stops paging early (252 of 677). */
    const val LIKED_BROWSE_ID = "VLLM"

    /** Like / un-like a track on the user's YouTube account. Call off the main thread.
     *  Returns true only when YouTube accepted the rating: post() already guarantees a 2xx parseable
     *  JSON (null = network error / non-2xx / rate-limit HTML), and when the response carries an
     *  explicit run status it must be STATUS_SUCCEEDED. One short-backoff retry on failure, matching
     *  home()'s transient-hiccup handling. */
    fun rate(videoId: String, liked: Boolean): Boolean {
        if (!AuthStore.connected) return false
        val endpoint = if (liked) "like/like" else "like/removelike"
        if (rateOnce(endpoint, videoId)) return true
        Thread.sleep(600)
        return rateOnce(endpoint, videoId)
    }

    private fun rateOnce(endpoint: String, videoId: String): Boolean {
        val body = JSONObject().apply {
            put("target", JSONObject().put("videoId", videoId))
            put("context", contextClient())
        }
        val root = post(endpoint, body, auth = true) ?: return false
        // Feedback-style confirmation when present ("status"/"runStatus": STATUS_SUCCEEDED); a
        // confirmation-less 2xx JSON still counts — like/like often replies with just actions.
        val status = root.s("status") ?: root.s("runStatus")
        return status == null || status == "STATUS_SUCCEEDED"
    }

    /** The ACCOUNT's like state for a track — "LIKE", "DISLIKE" or "INDIFFERENT" — via the
     *  authenticated "next" endpoint (its likeButtonRenderer carries likeStatus for the requested
     *  video). Null on ANY failure (not connected, offline, shape change) so callers treat it as
     *  "unknown" and change nothing. Call off the main thread. */
    fun likeStatus(videoId: String): String? {
        if (!AuthStore.connected) return null
        val root = post("next", JSONObject().apply {
            put("videoId", videoId)
            put("context", contextClient())
        }, auth = true) ?: return null
        return findLikeStatus(root, videoId)
    }

    /** Defensive walk for a "likeStatus" field with a known value. The queue's OTHER tracks encode
     *  their state under likeEndpoint."status", not "likeStatus", so the first hit is the requested
     *  video's likeButtonRenderer — but when a sibling "target" exists it must still match [videoId]. */
    private fun findLikeStatus(node: Any?, videoId: String): String? {
        when (node) {
            is JSONObject -> {
                val status = node.s("likeStatus")
                if (status != null && (status == "LIKE" || status == "DISLIKE" || status == "INDIFFERENT")) {
                    val target = node.o("target")?.s("videoId")
                    if (target == null || target == videoId) return status
                }
                val keys = node.keys()
                while (keys.hasNext()) findLikeStatus(node.opt(keys.next()), videoId)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findLikeStatus(node.opt(i), videoId)?.let { return it }
        }
        return null
    }

    /** Sign PRIVATE feeds when connected — the ONE auth rule shared by browse() and categoryPage().
     *  Personal playlists use VL… ids (Liked Music = VLLM, My Supermix, Discover Mix, My Mix N) and
     *  FEmusic_* feeds (including FEmusic_moods_and_genres_category…) need the cookie — that's what
     *  makes a signed-in user's category pages personalized. Public albums/playlists (MPRE…, OLAK…,
     *  MPLA…) stay anonymous so they still load if the session cookie has expired. */
    private fun signBrowse(browseId: String): Boolean =
        AuthStore.connected && (browseId.startsWith("VL") || browseId.startsWith("FEmusic_"))

    /** Fetch an album / playlist / artist page: header + all playable tracks found in the response.
     *  Private feeds (FEmusic_*, e.g. Liked Music) are signed with the session cookie. */
    fun browse(browseId: String): BrowseResult {
        // Hard failure throws (like the OkHttp IOException did before post() went nullable) so the caller's
        // runCatching shows its error path rather than a hollow "Playlist" page with zero tracks.
        val auth = signBrowse(browseId)
        val root = fetch(browseId, auth = auth)
            ?: throw IOException("browse $browseId unreachable — network error or rate-limited")
        val tracks = ArrayList<StreamItem>()
        collectTracks(root, tracks)
        // Long playlists arrive 100 tracks a page (Liked Music showed exactly its first 100): follow the
        // continuation token until the list is exhausted. Stops on a repeated token or a page that adds
        // nothing, so a stale token can't spin, and on a network failure keeps what it has.
        var continuation = findContinuation(root)
        val seen = HashSet<String>()
        var pages = 1
        while (continuation != null && seen.add(continuation.token) && pages < MAX_BROWSE_PAGES) {
            // One failed page used to end the list right there (Liked Music then stopped at 100): retry it twice.
            val c = continuation
            val page = fetchContinuation(c, auth)
                ?: run { Thread.sleep(750); fetchContinuation(c, auth) }
                ?: run { Thread.sleep(1500); fetchContinuation(c, auth) }
                ?: break
            pages++
            val before = tracks.size
            collectTracks(page, tracks)
            if (tracks.size == before) break
            continuation = findContinuation(page)
        }
        android.util.Log.i("PULSE", "browse $browseId: ${tracks.size} tracks over $pages page(s)")
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

    /** A playlist page's "more" token. YouTube uses two shapes: the newer continuationItemRenderer carries a
     *  continuationCommand token sent in the request BODY; the older nextContinuationData token goes in the
     *  URL (ctoken/continuation, type=next). */
    private class Continuation(val token: String, val inBody: Boolean)

    /** Cap on pages per browse — 100 tracks each, so 50 pages is a 5,000-song list. */
    private const val MAX_BROWSE_PAGES = 50

    /** The track shelf's continuation, if any. Searched inside the shelf first — the surrounding section list
     *  can carry its own (unrelated) continuation; a continuation page has no shelf wrapper, so fall back to
     *  the whole response. */
    private fun findContinuation(root: JSONObject): Continuation? {
        val shelf = findFirst(root, "musicPlaylistShelfRenderer") ?: findFirst(root, "musicShelfRenderer")
            ?: findFirst(root, "musicPlaylistShelfContinuation") ?: findFirst(root, "musicShelfContinuation")
        return findContinuationIn(shelf) ?: findContinuationIn(root)
    }

    private fun findContinuationIn(node: Any?): Continuation? {
        when (node) {
            is JSONObject -> {
                node.o("continuationCommand")?.s("token")?.let { return Continuation(it, inBody = true) }
                node.o("nextContinuationData")?.s("continuation")?.let { return Continuation(it, inBody = false) }
                val keys = node.keys()
                while (keys.hasNext()) findContinuationIn(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findContinuationIn(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun fetchContinuation(c: Continuation, auth: Boolean): JSONObject? {
        val body = JSONObject().put("context", contextClient())
        return if (c.inBody) {
            post("browse", body.put("continuation", c.token), auth)
        } else {
            val t = java.net.URLEncoder.encode(c.token, "UTF-8")
            post("browse", body, auth, query = "ctoken=$t&continuation=$t&type=next")
        }
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

    /** One "Moods & genres" category page: YT Music's own curated shelves for that chip, straight from
     *  the chip's clickCommand browseId (+ params). Signed via the same signBrowse() rule as browse() —
     *  category ids start with FEmusic_ so a connected user gets YT's PERSONALIZED picks. Reuses the
     *  home() shelf walker, so shelves of playlists/albums/tracks land in the HomeShelf/HomeCard models.
     *  Hard failure THROWS (matching browse()) so the category screen can show its error + Retry. */
    fun categoryPage(browseId: String, params: String?): List<HomeShelf> {
        val root = fetch(browseId, auth = signBrowse(browseId), params = params)
            ?: throw IOException("category $browseId unreachable — network error or rate-limited")
        val shelves = ArrayList<HomeShelf>()
        collectShelves(root, shelves)
        return shelves.dedupeShelves()
    }

    private fun fetch(browseId: String, auth: Boolean = true, params: String? = null): JSONObject? =
        post("browse", JSONObject().apply {
            put("browseId", browseId)
            // Category pages need the chip's opaque params token alongside the browseId — without it
            // YT answers FEmusic_moods_and_genres_category with an empty page. Absent for every other
            // browse, so existing callers are untouched.
            params?.let { put("params", it) }
            put("context", contextClient())
        }, auth)

    /** YouTube-assigned anonymous session id, captured from responseContext of ANY InnerTube response.
     *  Anonymous browse requests that don't echo it back commonly get a thin or completely EMPTY
     *  FEmusic_home, so we keep it for the life of the process and attach it to every anonymous call. */
    @Volatile
    private var visitorData: String? = null

    /** POSTs to InnerTube. Returns the parsed response, or null on HARD failure — network error, non-2xx
     *  status, or an unparseable body (YouTube serves HTML on rate-limit/captcha/consent/5xx). Callers that
     *  must never throw treat null like an empty response; home() uses it to tell "unreachable" from "empty". */
    private fun post(endpoint: String, body: JSONObject, auth: Boolean = true, query: String? = null): JSONObject? {
        // Echo the visitor id back on anonymous requests exactly like the web app does: both inside
        // context.client and as the X-Goog-Visitor-Id header. Signed-in requests are identified by
        // their cookie instead. Read once into a local so body and header can't disagree mid-race.
        val vd = if (!auth) visitorData else null
        vd?.let { body.o("context")?.o("client")?.put("visitorData", it) }
        val builder = okhttp3.Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?key=$KEY&prettyPrint=false" + (query?.let { "&$it" } ?: ""))
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
        vd?.let { builder.header("X-Goog-Visitor-Id", it) }
        // Cookie + SAPISIDHASH — the same auth the music.youtube.com web app sends. (OAuth Bearer
        // tokens stopped working for YT Music endpoints in Nov 2024.)
        if (auth) AuthStore.cookies?.let { c ->
            builder.header("Cookie", c)
            AuthStore.sapisidHash()?.let { builder.header("Authorization", it) }
            builder.header("X-Goog-AuthUser", "0")
        }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string()?.trimStart() ?: return null
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null   // HTML error page
                // Even error responses can carry a fresh visitor id — always harvest it.
                json.o("responseContext")?.s("visitorData")?.let { visitorData = it }
                if (resp.isSuccessful) json else null
            }
        } catch (e: IOException) {
            null   // offline / DNS / timeout — hard failure, never throw from here
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
