package app.pulse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.SearchState
import app.pulse.core.MoodCategory
import app.pulse.core.StreamItem
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.PlayingBars
import app.pulse.ui.components.ShimmerHost
import app.pulse.ui.components.SkeletonRow
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.Line12
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.RedW06
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/** Fallback tiles for the idle "Browse all" grid — shown until (or in case) the real YT Music
 *  moods & genres arrive, and their gradients double as the palette for fetched categories that
 *  carry no color of their own. */
private val GENRES = listOf(
    "Pop" to listOf(Color(0xFF2B2A5E), Color(0xFFC0455E)),
    "Hip-Hop" to listOf(Color(0xFF5A1F2E), Color(0xFFD1682F)),
    "Bollywood" to listOf(Color(0xFF6B243F), Color(0xFFC0455E)),
    "Nepali" to listOf(Color(0xFF123A4A), Color(0xFF3A7D78)),
    "Chill" to listOf(Color(0xFF4B2B6B), Color(0xFFB7623A)),
    "Lofi" to listOf(Color(0xFF1C3A5E), Color(0xFF5A3A8E)),
    "Workout" to listOf(Color(0xFF243B6E), Color(0xFF3A7D78)),
    "Party" to listOf(Color(0xFF3A5A2E), Color(0xFF8E7A3A)),
)

private val TABS = listOf("Top", "Songs", "Videos", "Albums", "Artists")

@Composable
fun SearchScreen(
    state: SearchState,
    currentUrl: String?,
    isPlaying: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onClearRecents: () -> Unit,
    onClearQuery: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onTab: (Int) -> Unit,
    onLoadCategories: () -> Unit,
    onRequestArt: (String) -> Unit,
    onOpenCategory: (MoodCategory) -> Unit,
    onDownload: (StreamItem) -> Unit,
    onMore: (StreamItem) -> Unit,
) {
    // Kick the moods & genres fetch the first time this tab composes — the ViewModel dedupes it to
    // at most one request per process, so re-entering the tab is free.
    LaunchedEffect(Unit) { onLoadCategories() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Bg2).border(1.dp, Line12, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Tx2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Songs, artists, albums, videos", color = Tx3, fontSize = 15.sp)
                BasicTextField(
                    value = state.query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = Tx0, fontSize = 15.sp), cursorBrush = SolidColor(Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.query.isNotEmpty()) {
                Box(Modifier.size(30.dp).clickable { onClearQuery() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "clear", tint = Tx2, modifier = Modifier.size(18.dp))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Same decision tree as before — the when now just picks a branch key so the switch
            // itself can crossfade (suggestions/idle/results fade in instead of popping).
            val branch = when {
                // Typing, pre-submit: live suggestions take over from the idle page until the user
                // commits a search (submit clears them, so results never fight this branch).
                !state.searched && state.query.isNotBlank() && state.suggestions.isNotEmpty() -> SearchBranch.Suggestions
                !state.searched -> SearchBranch.Idle
                else -> SearchBranch.Results
            }
            Crossfade(branch, animationSpec = AnimSpecs.fast(), label = "searchBranch") { b ->
                when (b) {
                    SearchBranch.Suggestions -> Suggestions(state.suggestions, onSearchFor)
                    SearchBranch.Idle -> Idle(state, onSearchFor, onClearRecents, onRequestArt, onOpenCategory)
                    SearchBranch.Results -> {
                        // This branch stays composed through its exit crossfade, but clearQuery wipes
                        // results/searched the instant the user taps X — so freeze the last state that
                        // had searched=true (same pattern as Suggestions below) and render THAT while
                        // exiting. Reading live state here instead would flip the inner branch to
                        // Empty mid-exit and ghost-flash `No results for ""` over the fade-out.
                        var lastSearched by remember { mutableStateOf(state) }
                        val s = if (state.searched) state else lastSearched
                        SideEffect { if (state.searched) lastSearched = state }
                        Column(Modifier.fillMaxSize()) {
                            ResultTabs(s.tab, onTab)
                            Box(Modifier.fillMaxSize()) {
                                val results = when {
                                    // In flight with nothing to show yet: skeleton rows, not a bare spinner.
                                    s.loading && s.results.isEmpty() -> ResultsBranch.Skeleton
                                    s.loading -> ResultsBranch.Spinner
                                    s.error != null -> ResultsBranch.Error
                                    s.results.isEmpty() -> ResultsBranch.Empty
                                    else -> ResultsBranch.List
                                }
                                Crossfade(results, animationSpec = AnimSpecs.fast(), label = "searchResults") { r ->
                                    when (r) {
                                        ResultsBranch.Skeleton -> SearchSkeleton()
                                        ResultsBranch.Spinner -> Box(Modifier.fillMaxSize()) {
                                            CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                                        }
                                        ResultsBranch.Error -> Hint("Couldn't search.\n${s.error ?: ""}")
                                        ResultsBranch.Empty -> Hint("No results for \"${s.query}\".")
                                        // isNotEmpty guard: List can only be current when s.results is
                                        // non-empty, but this lambda also re-runs for the OUTGOING
                                        // branch of the inner crossfade, whose list may be gone.
                                        ResultsBranch.List -> if (s.results.isNotEmpty()) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
                                            item { TopResult(s.results[0], s.results[0].url == currentUrl, isPlaying) { onPlay(s.results, 0) } }
                                            item { SectionLabel(if (s.tab == 2) "VIDEOS" else "SONGS") }
                                            itemsIndexed(s.results, key = { i, it -> it.url + i }) { i, item ->
                                                SearchRow(item, item.url == currentUrl, isPlaying, { onPlay(s.results, i) }, { onDownload(item) }, { onMore(item) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Branch key for the top-level idle/suggestions/results switch — lets the whole switch crossfade. */
private enum class SearchBranch { Suggestions, Idle, Results }

/** Branch key for the results area under the tabs. */
private enum class ResultsBranch { Skeleton, Spinner, Error, Empty, List }

/** In-flight placeholder: ONE ShimmerHost, ~8 fake rows mirroring SearchRow's geometry. */
@Composable
private fun SearchSkeleton() {
    ShimmerHost(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            // The extra 8.dp brings SkeletonRow's built-in 8.dp side padding up to this screen's 16.dp.
            repeat(8) { SkeletonRow(Modifier.padding(horizontal = 8.dp)) }
        }
    }
}

@Composable
private fun ResultTabs(selected: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TABS.forEachIndexed { i, t ->
            val active = i == selected
            Column(Modifier.height(44.dp).clickable { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(t, color = if (active) Tx0 else Tx2, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (active) Red else Color.Transparent))
            }
        }
    }
}

@Composable
private fun TopResult(item: StreamItem, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(104.dp).clip(RoundedCornerShape(16.dp)).background(Bg1).border(1.dp, Line08, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(item.thumbnailUrl, Modifier.size(72.dp), 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("TOP RESULT", color = Tx2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(item.title, color = Tx0, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (item.uploader.isNotBlank()) "Song · ${item.uploader}" else "Song", color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "play", tint = OnRed, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun SearchRow(item: StreamItem, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit, onDownload: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(if (isCurrent) RedW06 else Color.Transparent).clickable { onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Thumb(item.thumbnailUrl, Modifier.size(48.dp), 8.dp)
            if (isCurrent) Box(Modifier.matchParentSize().background(Color(0xB3000000), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { PlayingBars(playing = isPlaying) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = if (isCurrent) Red else Tx0, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.uploader, color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(36.dp).clickable { onDownload() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, "download", tint = Tx2, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.size(36.dp).clickable { onMore() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreVert, "more", tint = Tx2, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Idle(
    state: SearchState,
    onSearchFor: (String) -> Unit,
    onClearRecents: () -> Unit,
    onRequestArt: (String) -> Unit,
    onOpenCategory: (MoodCategory) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 150.dp)) {
        // Recents appear with a subtle fade (+ expand, so the layout below doesn't snap). The last
        // non-empty list is kept in remembered state — same pattern as MiniPlayer — so "Clear"
        // fades real rows out instead of collapsing an already-empty section.
        var lastRecents by remember { mutableStateOf(state.recents) }
        val recents = if (state.recents.isNotEmpty()) state.recents else lastRecents
        SideEffect { if (state.recents.isNotEmpty()) lastRecents = state.recents }
        AnimatedVisibility(
            visible = state.recents.isNotEmpty(),
            enter = fadeIn(AnimSpecs.normal()) + expandVertically(AnimSpecs.normal()),
            exit = fadeOut(AnimSpecs.fast()) + shrinkVertically(AnimSpecs.fast()),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
                    Text("Clear", color = Tx1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onClearRecents() })
                }
                recents.forEach { r ->
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable { onSearchFor(r) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = Tx3, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(r, color = Tx0, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text("BROWSE ALL", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
        // Real YT Music moods & genres once the once-per-process fetch lands; the fixed GENRES list
        // covers cold start and offline so the grid is never blank. This section lives inside the
        // screen's verticalScroll Column, so the grid stays CHUNKED NON-LAZY rows — a LazyVerticalGrid
        // nested in an unbounded scrollable would crash on measure.
        val tiles = if (state.categories.isNotEmpty())
            state.categories.mapIndexed { i, c -> GenreTileData(c.name, tileColors(c, i), c) }
        else GENRES.map { (name, colors) -> GenreTileData(name, colors, null) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { t ->
                        GenreTile(t.name, t.colors, state.categoryArt[t.name], Modifier.weight(1f), onRequestArt) {
                            // A real YT category opens ITS curated page; only chips without a click
                            // target (and the fixed fallback tiles) keep the old search-the-name tap.
                            val cat = t.category
                            if (cat?.browseId != null) onOpenCategory(cat) else onSearchFor(t.name)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One "Browse all" tile: display name + gradient palette + the fetched category (null for the fixed
 *  fallback tiles, which have no YT click target). */
private data class GenreTileData(val name: String, val colors: List<Color>, val category: MoodCategory?)

/** Gradient for a fetched category tile: YT's own solid accent (an unsigned ARGB long) darkened into
 *  a two-stop gradient when served, else cycling through the fixed GENRES palettes by position. */
private fun tileColors(c: MoodCategory, index: Int): List<Color> {
    if (c.color != 0L) {
        val base = Color(c.color.toInt())
        return listOf(base, lerp(base, Color.Black, 0.35f))
    }
    return GENRES[index % GENRES.size].second
}

@Composable
private fun Suggestions(suggestions: List<String>, onPick: (String) -> Unit) {
    // Submit clears the live list before this branch's exit crossfade finishes, so keep the last
    // non-empty list in remembered state and fade those rows out instead of a blank column. While
    // the branch is actually shown the list is never empty (the when-condition requires it), so
    // no stale suggestions can ever be visible.
    var last by remember { mutableStateOf(suggestions) }
    val shown = if (suggestions.isNotEmpty()) suggestions else last
    SideEffect { if (suggestions.isNotEmpty()) last = suggestions }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 150.dp)) {
        shown.forEach { s ->
            Row(Modifier.fillMaxWidth().height(48.dp).clickable { onPick(s) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Tx3, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(s, color = Tx0, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Bottom scrim keeping the tile name legible over full-bleed cover art (constant — allocate once). */
private val TileScrim = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x33000000), 1f to Color(0xC6000000))

@Composable
private fun GenreTile(name: String, colors: List<Color>, artUrl: String?, modifier: Modifier, onRequestArt: (String) -> Unit, onClick: () -> Unit) {
    // Lazily resolve this tile's artwork from YT's curated category page — the ViewModel dedupes per
    // name and caps concurrency, so composing the whole grid at once is fine.
    LaunchedEffect(name) { onRequestArt(name) }
    // Cached per palette: GenreTile is never skippable (List<Color> param), so every categoryArt
    // arrival recomposes the whole grid — the gradient must not be re-allocated per tile each time.
    val accent = remember(colors) { Brush.linearGradient(colors) }
    Box(modifier.height(104.dp).clip(RoundedCornerShape(12.dp)).background(Bg2).border(1.dp, Line08, RoundedCornerShape(12.dp)).clickable { onClick() }) {
        // The original colored-tile look stays composed underneath as placeholder and offline
        // fallback, so the cover crossfades over it instead of popping onto a bare surface.
        Text(name, color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 10.dp).size(64.dp).rotate(18f).clip(RoundedCornerShape(8.dp))
                .background(accent),
        )
        var artLoaded by remember(artUrl) { mutableStateOf(false) }
        if (artUrl != null) {
            // Full-bleed cover from YT's own curation, crossfaded in by Coil over the colored tile.
            val context = LocalContext.current
            val request = remember(artUrl) { ImageRequest.Builder(context).data(artUrl).crossfade(200).build() }
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Crop,
                onState = { s -> artLoaded = s is AsyncImagePainter.State.Success },
                modifier = Modifier.matchParentSize(),
            )
        }
        // Scrim + relocated name fade in only once the bitmap has actually loaded — a resolved URL
        // alone must not darken the placeholder while the image is still in flight.
        AnimatedVisibility(visible = artLoaded, modifier = Modifier.matchParentSize(), enter = fadeIn(AnimSpecs.normal()), exit = fadeOut(AnimSpecs.fast())) {
            Box(Modifier.fillMaxSize().background(TileScrim)) {
                Text(
                    name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tx3, fontSize = 13.sp)
    }
}
