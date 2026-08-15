package app.pulse.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.pulse.HomeViewModel
import app.pulse.LocalMusicViewModel
import app.pulse.SearchViewModel
import app.pulse.SettingsViewModel
import app.pulse.core.AUDIO_PERMISSION
import app.pulse.core.AuthStore
import app.pulse.core.BrowseResult
import app.pulse.core.HomeShelf
import app.pulse.core.MoodCategory
import app.pulse.core.hasAudioPermission
import app.pulse.core.FavoritesStore
import app.pulse.core.PlaylistStore
import app.pulse.core.StreamItem
import app.pulse.core.YtMusic
import app.pulse.download.DlFormat
import app.pulse.download.DlStatus
import app.pulse.download.DownloadItem
import app.pulse.download.DownloadManager
import app.pulse.playback.PlayerViewModel
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.AnimatedOverlay
import app.pulse.ui.components.BottomBar
import app.pulse.ui.components.MiniPlayer
import app.pulse.ui.components.PulseTab
import app.pulse.ui.screens.DetailScreen
import app.pulse.ui.screens.DownloadsScreen
import app.pulse.ui.screens.HomeScreen
import app.pulse.ui.screens.LibraryScreen
import app.pulse.ui.screens.LoginScreen
import app.pulse.ui.screens.AddToPlaylistSheet
import app.pulse.ui.screens.CategoryScreen
import app.pulse.ui.screens.LyricsScreen
import app.pulse.ui.screens.NewPlaylistDialog
import app.pulse.ui.screens.NowPlayingScreen
import app.pulse.ui.screens.OverflowSheet
import app.pulse.ui.screens.QueueSheet
import app.pulse.ui.screens.SearchScreen
import app.pulse.ui.screens.SettingsScreen
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2

// AnimatedContent keys for the main content layer (tabs use the PulseTab value itself).
private const val DETAIL_LAYER = "detail"
private const val CATEGORY_LAYER = "category"

@Composable
fun PulseRoot(homeVm: HomeViewModel, searchVm: SearchViewModel, playerVm: PlayerViewModel, settingsVm: SettingsViewModel, localVm: LocalMusicViewModel) {
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val playerUi by playerVm.ui.collectAsStateWithLifecycle()
    val downloads by DownloadManager.items.collectAsStateWithLifecycle()
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    val connected by AuthStore.connectedFlow.collectAsStateWithLifecycle()
    val playlists by PlaylistStore.playlists.collectAsStateWithLifecycle()
    val likedSet by FavoritesStore.liked.collectAsStateWithLifecycle()
    val localTracks by localVm.tracks.collectAsStateWithLifecycle()
    val localScanning by localVm.scanning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Device music-library permission + one-shot scan for the Downloads "On this device" section.
    var hasAudio by remember { mutableStateOf(hasAudioPermission(context)) }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudio = granted
        if (granted) localVm.scan(force = true)
    }
    LaunchedEffect(Unit) { if (hasAudio) localVm.scan() }

    // Re-check the audio permission on every RESUME, so a grant made from system Settings (after the in-app
    // request was permanently denied) is picked up without needing a full app restart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasAudioPermission(context)
                if (granted != hasAudio) { hasAudio = granted; if (granted) localVm.scan(force = true) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var tab by remember { mutableStateOf(PulseTab.Home) }
    var nowPlaying by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf<String?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAddPlaylist by remember { mutableStateOf(false) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var overflowTrack by remember { mutableStateOf<StreamItem?>(null) }
    // The track the DownloadPicker was opened FOR. The picker is reachable from NowPlaying (current
    // track) AND from any overflow sheet (that sheet's track) — resolving the target at pick time
    // via playerUi.current downloaded the wrong track from Search/Detail overflow menus.
    var pickerTarget by remember { mutableStateOf<StreamItem?>(null) }
    var detailBrowseId by remember { mutableStateOf<String?>(null) }
    var detailResult by remember { mutableStateOf<BrowseResult?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf(false) }
    // Bumped by DetailScreen's Retry button to re-run the effect for the same browseId.
    var detailAttempt by remember { mutableStateOf(0) }
    // The tapped "Moods & genres" tile — non-null shows CategoryScreen (YT's curated page for that
    // category) above the tab content but below the Detail/NowPlaying/sheet overlays.
    var categoryTarget by remember { mutableStateOf<MoodCategory?>(null) }
    var categoryShelves by remember { mutableStateOf<List<HomeShelf>?>(null) }
    var categoryLoading by remember { mutableStateOf(false) }
    var categoryError by remember { mutableStateOf(false) }
    // Bumped by CategoryScreen's Retry button to re-run the effect for the same category.
    var categoryAttempt by remember { mutableStateOf(0) }
    // Last non-null category, kept so CategoryScreen can still draw its title during the exit
    // animation (categoryTarget itself is nulled instantly on back/dismiss). Never cleared —
    // categoryShelves/detailResult follow the same keep-until-next-open pattern.
    var lastCategory by remember { mutableStateOf<MoodCategory?>(null) }
    if (categoryTarget != null && categoryTarget != lastCategory) lastCategory = categoryTarget

    LaunchedEffect(categoryTarget, categoryAttempt) {
        val cat = categoryTarget
        val id = cat?.browseId
        if (cat != null && id != null) {
            categoryLoading = true
            categoryShelves = null
            categoryError = false
            // categoryPage() throws on hard failure (like browse()) — surface it as the error state
            // with Retry instead of a spinner that never resolves.
            val r = withContext(Dispatchers.IO) { runCatching { YtMusic.categoryPage(id, cat.params) } }
            categoryShelves = r.getOrNull()
            categoryError = r.isFailure
            categoryLoading = false
        }
    }

    LaunchedEffect(detailBrowseId, detailAttempt) {
        val id = detailBrowseId
        if (id != null) {
            detailLoading = true
            detailResult = null
            detailError = false
            // browse() throws on network failure / rate-limit HTML (instead of returning a hollow
            // "Playlist, 0 tracks" page) — keep the failure distinct from "still loading", or every
            // rate-limited open would sit on DetailScreen's spinner forever with no way out.
            val r = withContext(Dispatchers.IO) { runCatching { YtMusic.browse(id) } }
            detailResult = r.getOrNull()
            detailError = r.isFailure
            detailLoading = false
        }
    }

    val play = remember(playerVm) { { list: List<StreamItem>, i: Int -> playerVm.playList(list, i) } }
    val playLocal = remember(playerVm) {
        { dl: DownloadItem ->
            dl.filePath?.let { fp ->
                // Prefer the downloaded side-car cover as a file:// uri — Coil (mini-player/NowPlaying)
                // and Media3's notification bitmap loader both read it, so art shows fully offline.
                val art = dl.artPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                    ?.let { android.net.Uri.fromFile(it).toString() } ?: dl.thumbnailUrl
                playerVm.playLocalFile(dl.title, dl.uploader, art, fp)
            }
            Unit
        }
    }
    val removeDl = remember { { dl: DownloadItem -> DownloadManager.remove(dl) } }

    val startConnect: () -> Unit = { showLogin = true }

    // Back navigation — exactly one is enabled at a time. The when-cascade mirrors z-order top-down,
    // and its conditions MUST stay consistent with the enabled-expression: categoryTarget sits BELOW
    // detail (a category card can open DetailScreen on top) and ABOVE the plain tab.
    BackHandler(enabled = showLogin || showNewPlaylist || showAddPlaylist || showOverflow || showPicker || showLyrics || showQueue || showSettings || nowPlaying || detailBrowseId != null || categoryTarget != null || tab != PulseTab.Home) {
        when {
            showLogin -> showLogin = false
            showNewPlaylist -> showNewPlaylist = false
            showAddPlaylist -> showAddPlaylist = false
            showOverflow -> showOverflow = false
            showPicker -> showPicker = false
            showLyrics -> showLyrics = false
            showQueue -> showQueue = false
            showSettings -> showSettings = false
            nowPlaying -> nowPlaying = false
            detailBrowseId != null -> detailBrowseId = null
            categoryTarget != null -> categoryTarget = null
            else -> tab = PulseTab.Home
        }
    }

    LaunchedEffect(playerUi.error) {
        if (playerUi.error != null) Toast.makeText(context, playerUi.error, Toast.LENGTH_SHORT).show()
    }

    // When playback is fully cleared (notification Close), close any open player overlay so it doesn't
    // auto-reopen the next time a track starts.
    LaunchedEffect(playerUi.hasCurrent) {
        if (!playerUi.hasCurrent) { nowPlaying = false; showQueue = false; showLyrics = false }
    }
    val curUrl = playerUi.current?.url

    // Account-like bookkeeping, session-scoped and in-memory. likeStatusChecked: videoIds whose
    // account likeStatus was already fetched this session — one "next" call per track, ever.
    // userToggledLikes: videoIds the user hearted/unhearted themselves this session — account
    // reconciliation must never overwrite those (the user's own tap is fresher than any fetch).
    val likeStatusChecked = remember { mutableSetOf<String>() }
    val userToggledLikes = remember { mutableSetOf<String>() }

    // Reflect the ACCOUNT's truth on the heart: when the playing track changes while connected, ask
    // YouTube for its like state once per videoId per session and reconcile the local FavoritesStore
    // (add on LIKE, remove on INDIFFERENT — idempotent, so no flicker when they already agree).
    // Null status (offline, expired session, shape change) changes nothing.
    LaunchedEffect(curUrl, connected) {
        val url = curUrl ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        // Local files have no ?v= — getQueryParameter returns null and we bail.
        val vid = runCatching { android.net.Uri.parse(url).getQueryParameter("v") }.getOrNull() ?: return@LaunchedEffect
        if (vid in userToggledLikes || !likeStatusChecked.add(vid)) return@LaunchedEffect
        val status = withContext(Dispatchers.IO) { runCatching { YtMusic.likeStatus(vid) }.getOrNull() }
        if (vid in userToggledLikes) return@LaunchedEffect   // user tapped the heart while the fetch was in flight
        when (status) {
            "LIKE" -> FavoritesStore.add(url)
            "INDIFFERENT" -> FavoritesStore.remove(url)
        }
    }

    LaunchedEffect(showLyrics, curUrl) {
        if (showLyrics && curUrl != null) {
            val vid = android.net.Uri.parse(curUrl).getQueryParameter("v")
            if (vid != null) {
                lyricsLoading = true
                lyricsText = null
                lyricsText = withContext(Dispatchers.IO) { runCatching { YtMusic.lyrics(vid) }.getOrNull() }
                lyricsLoading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        Box(Modifier.fillMaxSize()) {
            // Layer key: detail wins over category wins over the plain tab — same precedence the
            // old if/else chain had. AnimatedContent keeps the OUTGOING layer composed during its
            // exit; the data it reads (detailResult / categoryShelves / lastCategory) is only
            // cleared on the NEXT open, never on close, so the exit frames stay fully drawn.
            val contentLayer: Any = when {
                detailBrowseId != null -> DETAIL_LAYER
                categoryTarget != null -> CATEGORY_LAYER
                else -> tab
            }
            AnimatedContent(
                targetState = contentLayer,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val enteringOverlay = targetState == DETAIL_LAYER || targetState == CATEGORY_LAYER
                    val leavingOverlay = initialState == DETAIL_LAYER || initialState == CATEGORY_LAYER
                    val transform = when {
                        // Detail closing back to the Category beneath it: play the CLOSE motion —
                        // the revealed category simply fades in while the exiting detail (kept on
                        // top via its entry zIndex) fades + sinks. Must precede enteringOverlay,
                        // which matches this pair too and would play the open transform on a back.
                        initialState == DETAIL_LAYER && targetState == CATEGORY_LAYER ->
                            fadeIn(AnimSpecs.normal()) togetherWith (fadeOut(AnimSpecs.fast()) + slideOutVertically(AnimSpecs.fast()) { it / 20 })
                        // Overlay opens: fade + slight rise over a fading tab.
                        enteringOverlay -> (fadeIn(AnimSpecs.normal()) + slideInVertically(AnimSpecs.normal()) { it / 20 }) togetherWith fadeOut(AnimSpecs.fast())
                        // Overlay closes: it fades + sinks (kept on top via its entry zIndex).
                        leavingOverlay -> fadeIn(AnimSpecs.normal()) togetherWith (fadeOut(AnimSpecs.fast()) + slideOutVertically(AnimSpecs.fast()) { it / 20 })
                        // Tab <-> tab: plain 150 ms crossfade.
                        else -> fadeIn(AnimSpecs.fast()) togetherWith fadeOut(AnimSpecs.fast())
                    }
                    // Detail above category above tabs — an exiting layer keeps the zIndex it
                    // entered with, so a closing Detail slides out OVER what it reveals.
                    transform.targetContentZIndex = when (targetState) {
                        DETAIL_LAYER -> 2f
                        CATEGORY_LAYER -> 1f
                        else -> 0f
                    }
                    transform
                },
                label = "contentLayer",
            ) { layer ->
                if (layer == DETAIL_LAYER) {
                    DetailScreen(
                        result = detailResult, loading = detailLoading, error = detailError, currentUrl = curUrl, isPlaying = playerUi.isPlaying,
                        onBack = { detailBrowseId = null },
                        onRetry = { detailAttempt++ },
                        onPlay = { list, i -> playerVm.playList(list, i, detailResult?.title ?: "Emma") },
                        onShuffle = { playerVm.playList(it.shuffled(), 0, detailResult?.title ?: "Emma") },
                        onDownloadAll = { list, audio -> list.forEach { DownloadManager.enqueue(context, it.url, it.title, it.uploader, it.thumbnailUrl, if (audio) DlFormat.M4A else DlFormat.MP4) } },
                        onDownloadTrack = { t, audio -> DownloadManager.enqueue(context, t.url, t.title, t.uploader, t.thumbnailUrl, if (audio) DlFormat.M4A else DlFormat.MP4); Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show() },
                        onMore = { detailResult?.tracks?.firstOrNull()?.let { overflowTrack = it; showOverflow = true } },
                    )
                } else if (layer == CATEGORY_LAYER) {
                    // Below DetailScreen (a category card can open one on top), above the tab content.
                    CategoryScreen(
                        title = (categoryTarget ?: lastCategory)?.name ?: "",
                        shelves = categoryShelves, loading = categoryLoading, error = categoryError, currentUrl = curUrl,
                        onBack = { categoryTarget = null },
                        onRetry = { categoryAttempt++ },
                        onPlay = play,
                        onOpenDetail = { detailBrowseId = it },
                        onBrowse = { q -> categoryTarget = null; searchVm.searchFor(q); tab = PulseTab.Search },
                    )
                } else when (layer as PulseTab) {
                PulseTab.Home -> HomeScreen(
                    homeState, curUrl, play,
                    onBrowse = { q -> searchVm.searchFor(q); tab = PulseTab.Search },
                    onOpenDetail = { detailBrowseId = it },
                    onRetry = { homeVm.load() },
                    onSettings = { showSettings = true },
                    onOpenDownloads = { tab = PulseTab.Downloads },
                )
                PulseTab.Search -> SearchScreen(
                    searchState, curUrl, playerUi.isPlaying,
                    onQuery = searchVm::setQuery, onSearch = searchVm::search,
                    onSearchFor = { q -> searchVm.searchFor(q) }, onClearRecents = searchVm::clearRecents,
                    onClearQuery = searchVm::clearQuery, onPlay = play,
                    onTab = searchVm::setTab,
                    onLoadCategories = searchVm::loadCategories,
                    onRequestArt = searchVm::requestCategoryArt,
                    onOpenCategory = { categoryTarget = it },
                    onDownload = { t -> DownloadManager.enqueue(context, t.url, t.title, t.uploader, t.thumbnailUrl, if (settings.defaultVideo) DlFormat.MP4 else DlFormat.M4A); Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show() },
                    onMore = { t -> overflowTrack = t; showOverflow = true },
                )
                PulseTab.Library -> LibraryScreen(
                    downloads, playlists, onPlay = playLocal,
                    onPlayPlaylist = { list -> play(list, 0) },
                    onOpenDownloads = { tab = PulseTab.Downloads },
                    connected = connected,
                    onOpenLiked = { if (connected) detailBrowseId = YtMusic.LIKED_BROWSE_ID else startConnect() },
                )
                PulseTab.Downloads -> DownloadsScreen(
                    items = downloads,
                    localTracks = localTracks,
                    hasAudioPermission = hasAudio,
                    scanningLocal = localScanning,
                    onRequestPermission = { audioPermLauncher.launch(AUDIO_PERMISSION) },
                    onRescanLocal = { localVm.scan(force = true) },
                    onPlayLocal = { i -> playerVm.playList(localTracks, i, "On this device") },
                    onPlay = playLocal, onRemove = removeDl,
                    onRetry = { DownloadManager.enqueue(context, it.url, it.title, it.uploader, it.thumbnailUrl, it.format) },
                    onBrowse = { tab = PulseTab.Home },
                )
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // MiniPlayer pops in (expand + fade, slight spring overshoot) when a track first
            // exists and shrinks away when playback is cleared. MiniPlayer itself remembers the
            // last track so it stays drawn during the exit animation — but the dying bar is still
            // hit-testable, so onTap re-checks hasCurrent: an unguarded tap would arm nowPlaying
            // AFTER the hasCurrent cleanup effect already ran (its Boolean key won't rerun),
            // leaving a stale flag that eats back presses and pops NowPlaying open on the next play.
            AnimatedOverlay(visible = playerUi.hasCurrent, enter = AnimSpecs.miniPlayerEnter, exit = AnimSpecs.miniPlayerExit) {
                MiniPlayer(playerUi, onTap = { if (playerUi.hasCurrent) nowPlaying = true }, onPlayPause = { playerVm.togglePlay() }, onNext = { playerVm.next() })
            }
            // Tapping a bottom tab must also dismiss any open album/playlist/detail/category overlay —
            // otherwise those (which win the branches above the `when (tab)`) keep painting over the tab.
            BottomBar(tab) { selected -> detailBrowseId = null; categoryTarget = null; tab = selected }
        }

        // NowPlaying: slide up / slide down. The screen stays composed through the exit slide and
        // reads playerUi, which stays valid (it draws a plain black box if current is null).
        AnimatedOverlay(visible = nowPlaying && playerUi.hasCurrent, enter = AnimSpecs.nowPlayingEnter, exit = AnimSpecs.nowPlayingExit) {
            NowPlayingScreen(
                ui = playerUi,
                onClose = { nowPlaying = false },
                onPlayPause = { playerVm.togglePlay() },
                onNext = { playerVm.next() },
                onPrev = { playerVm.prev() },
                onSeek = { playerVm.seekTo(it) },
                // hasCurrent / current guards: the screen stays clickable through its 250 ms exit
                // slide after clearAll(), and the hasCurrent cleanup effect above never reruns for
                // flags armed AFTER it fired — an unguarded tap on the dying screen would auto-open
                // the overlay on the next play (or open it for a null track via More/Download).
                onDownload = { playerUi.current?.let { pickerTarget = it; showPicker = true } },
                onLyrics = { if (playerUi.hasCurrent) showLyrics = true },
                onQueue = { if (playerUi.hasCurrent) showQueue = true },
                onMore = { playerUi.current?.let { overflowTrack = it; showOverflow = true } },
                player = playerVm.exoPlayer(),
                onToggleVideo = { playerVm.toggleVideoMode() },
                source = playerUi.source,
                liked = curUrl != null && curUrl in likedSet,
                onToggleLike = {
                    curUrl?.let { url ->
                        val nowLiked = url !in likedSet
                        FavoritesStore.toggle(url)   // offline-first: the heart flips locally right away, always
                        // Mark the tap even when DISCONNECTED — the reconciliation effect re-runs on a
                        // mid-session login, and an unmarked offline toggle would get undone by the
                        // account fetch. The user's own tap is fresher than any fetch, always.
                        val vid = runCatching { android.net.Uri.parse(url).getQueryParameter("v") }.getOrNull()
                        if (vid != null) userToggledLikes.add(vid)
                        // Mirror the like onto the user's YouTube account so Liked Music stays in sync.
                        if (connected && vid != null) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { YtMusic.rate(vid, nowLiked) }.getOrDefault(false) }
                                // Keep the local state either way (reconciliation can re-sync a later
                                // session) — just tell the user the account didn't get it.
                                if (!ok) Toast.makeText(context, "Couldn't sync like to YouTube", Toast.LENGTH_SHORT).show()
                            }
                        }
                        // Not connected: local-only, silently — exactly today's behavior.
                    }
                },
                onShuffle = { playerVm.toggleShuffle() },
                onRepeat = { playerVm.cycleRepeat() },
            )
        }

        // Bottom sheets: scrim (inside the sheet composable) fades with the subtree while the
        // whole thing slides up a third — no edits needed inside the Sheet scaffolds.
        AnimatedOverlay(visible = showQueue && playerUi.hasCurrent, enter = AnimSpecs.sheetEnter, exit = AnimSpecs.sheetExit) {
            QueueSheet(
                items = playerUi.items, currentIndex = playerUi.index, isPlaying = playerUi.isPlaying,
                onDismiss = { showQueue = false },
                onPlayIndex = { playerVm.playList(playerUi.items, it) },
            )
        }

        AnimatedOverlay(visible = showLyrics && playerUi.hasCurrent) {
            val cur = playerUi.current
            LyricsScreen(
                title = cur?.title ?: "", artist = cur?.uploader ?: "",
                lyrics = lyricsText, loading = lyricsLoading, onClose = { showLyrics = false },
            )
        }

        AnimatedOverlay(visible = showSettings) {
            Box(Modifier.fillMaxSize().background(Bg0)) {
                SettingsScreen(
                    s = settings,
                    completedDownloads = downloads.count { it.status == DlStatus.Completed },
                    connected = connected,
                    onConnect = startConnect,
                    onDisconnect = { scope.launch { AuthStore.clear(context); homeVm.load() } },
                    onBack = { showSettings = false },
                    onSetVideo = { settingsVm.setVideo(it) },
                    onSetWifi = { settingsVm.setWifiOnly(it) },
                    onSetPreferVideo = { settingsVm.setPreferVideo(it) },
                    onSetMax = { settingsVm.setMaxConcurrent(it) },
                )
            }
        }

        AnimatedOverlay(visible = showLogin) {
            Box(Modifier.fillMaxSize().background(Bg0)) {
                LoginScreen(
                    onConnected = { cookies ->
                        scope.launch {
                            AuthStore.save(context, cookies)   // flips AuthStore.connected → HomeViewModel re-fetches
                            showLogin = false
                            Toast.makeText(context, "Connected — your home is personalized now", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { showLogin = false },
                )
            }
        }

        // overflowTrack is never cleared on dismiss (only replaced on the next open), so the
        // sheet keeps its row content through the exit slide.
        AnimatedOverlay(visible = showOverflow, enter = AnimSpecs.sheetEnter, exit = AnimSpecs.sheetExit) {
            overflowTrack?.let { t ->
                OverflowSheet(
                    item = t,
                    onDismiss = { showOverflow = false },
                    onAddToPlaylist = { showOverflow = false; showAddPlaylist = true },
                    onAddToQueue = { playerVm.addToQueue(t); showOverflow = false; Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show() },
                    onDownload = { pickerTarget = t; showOverflow = false; showPicker = true },
                    onShare = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t.url) }, "Share"))
                        showOverflow = false
                    },
                )
            }
        }

        // Declared AFTER the overflow sheet: its onDownload swaps sheets in one handler, so both
        // animations run at once and Box order decides who's on top — the entering picker must
        // rise OVER the exiting overflow + scrim (like overflow -> AddToPlaylist below), not
        // hidden beneath them. Position relative to the other overlays is inert.
        AnimatedOverlay(visible = showPicker, enter = AnimSpecs.sheetEnter, exit = AnimSpecs.sheetExit) {
            DownloadPicker(
                onDismiss = { showPicker = false; pickerTarget = null },
                onPick = { fmt ->
                    pickerTarget?.let { c ->
                        if (c.url.startsWith("content://")) {
                            // A device-library track — it's already stored locally; there's nothing to download.
                            Toast.makeText(context, "This song is already on your device", Toast.LENGTH_SHORT).show()
                        } else {
                            DownloadManager.enqueue(context, c.url, c.title, c.uploader, c.thumbnailUrl, fmt)
                            Toast.makeText(context, "Download started — see the Downloads tab", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showPicker = false
                    pickerTarget = null
                },
            )
        }

        AnimatedOverlay(visible = showAddPlaylist, enter = AnimSpecs.sheetEnter, exit = AnimSpecs.sheetExit) {
            AddToPlaylistSheet(
                playlists = playlists,
                onDismiss = { showAddPlaylist = false },
                onNew = { showNewPlaylist = true },
                onAdd = { pid -> overflowTrack?.let { PlaylistStore.addTrack(pid, it) }; showAddPlaylist = false; Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show() },
            )
        }

        // Centered dialog, not sheet-like: fade + subtle scale. Its text-field state still resets
        // per open because AnimatedVisibility disposes content once fully hidden (same as before).
        AnimatedOverlay(visible = showNewPlaylist, enter = AnimSpecs.dialogEnter, exit = AnimSpecs.dialogExit) {
            NewPlaylistDialog(
                onDismiss = { showNewPlaylist = false },
                onCreate = { name ->
                    val id = PlaylistStore.create(name)
                    overflowTrack?.let { PlaylistStore.addTrack(id, it) }
                    showNewPlaylist = false; showAddPlaylist = false
                    Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun DownloadPicker(onDismiss: () -> Unit, onPick: (DlFormat) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onDismiss() })
        // Panel-level tap consumer (this picker re-implements the scrim/panel markup rather than
        // going through OverflowSheets' Sheet scaffold): taps on the panel's title/padding must
        // neither reach the scrim's dismiss nor pass through; the PickerRows are children and win.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Bg2).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(20.dp),
        ) {
            Text("Download", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            PickerRow(DlFormat.MP4, "Best quality video, larger file", recommended = true, onPick)
            PickerRow(DlFormat.M4A, "Audio only, original quality", recommended = false, onPick)
            PickerRow(DlFormat.MP3, "Audio, converted to MP3 (universal)", recommended = false, onPick)
        }
    }
}

@Composable
private fun PickerRow(fmt: DlFormat, subtitle: String, recommended: Boolean, onPick: (DlFormat) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPick(fmt) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (fmt == DlFormat.MP4) Icons.Rounded.Videocam else Icons.Rounded.MusicNote, null, tint = Tx0, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(fmt.label, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Tx2, fontSize = 11.sp)
        }
        if (recommended) Text("DEFAULT", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
