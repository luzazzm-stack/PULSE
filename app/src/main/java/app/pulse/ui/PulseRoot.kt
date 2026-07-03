package app.pulse.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.HomeViewModel
import app.pulse.SearchViewModel
import app.pulse.SettingsViewModel
import app.pulse.core.StreamItem
import app.pulse.download.DlFormat
import app.pulse.download.DlStatus
import app.pulse.download.DownloadItem
import app.pulse.download.DownloadManager
import app.pulse.playback.PlayerViewModel
import app.pulse.ui.components.BottomBar
import app.pulse.ui.components.MiniPlayer
import app.pulse.ui.components.PulseTab
import app.pulse.ui.screens.DownloadsScreen
import app.pulse.ui.screens.HomeScreen
import app.pulse.ui.screens.LibraryScreen
import app.pulse.ui.screens.NowPlayingScreen
import app.pulse.ui.screens.SearchScreen
import app.pulse.ui.screens.SettingsScreen
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2

@Composable
fun PulseRoot(homeVm: HomeViewModel, searchVm: SearchViewModel, playerVm: PlayerViewModel, settingsVm: SettingsViewModel) {
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val playerUi by playerVm.ui.collectAsStateWithLifecycle()
    val downloads by DownloadManager.items.collectAsStateWithLifecycle()
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(PulseTab.Home) }
    var nowPlaying by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val play = remember(playerVm) { { list: List<StreamItem>, i: Int -> playerVm.playList(list, i) } }
    val playLocal = remember(playerVm) {
        { dl: DownloadItem -> dl.filePath?.let { playerVm.playLocalFile(dl.title, dl.uploader, dl.thumbnailUrl, it) }; Unit }
    }
    val removeDl = remember { { dl: DownloadItem -> DownloadManager.remove(dl) } }

    // Back navigation — exactly one is enabled at a time
    BackHandler(enabled = showPicker) { showPicker = false }
    BackHandler(enabled = !showPicker && showSettings) { showSettings = false }
    BackHandler(enabled = !showPicker && !showSettings && nowPlaying) { nowPlaying = false }
    BackHandler(enabled = !showPicker && !showSettings && !nowPlaying && tab != PulseTab.Home) { tab = PulseTab.Home }

    LaunchedEffect(playerUi.error) {
        if (playerUi.error != null) Toast.makeText(context, playerUi.error, Toast.LENGTH_SHORT).show()
    }
    val curUrl = playerUi.current?.url

    Box(Modifier.fillMaxSize().background(Bg0)) {
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                PulseTab.Home -> HomeScreen(
                    homeState, curUrl, play,
                    onBrowse = { q -> searchVm.searchFor(q); tab = PulseTab.Search },
                    onRetry = { homeVm.load() },
                    onSettings = { showSettings = true },
                )
                PulseTab.Search -> SearchScreen(
                    searchState, curUrl, playerUi.isPlaying,
                    onQuery = searchVm::setQuery, onSearch = searchVm::search,
                    onSearchFor = { q -> searchVm.searchFor(q) }, onClearRecents = searchVm::clearRecents,
                    onClearQuery = searchVm::clearQuery, onPlay = play,
                )
                PulseTab.Library -> LibraryScreen(downloads, onPlay = playLocal, onOpenDownloads = { tab = PulseTab.Downloads })
                PulseTab.Downloads -> DownloadsScreen(
                    downloads, onPlay = playLocal, onRemove = removeDl,
                    onRetry = { DownloadManager.enqueue(context, it.url, it.title, it.uploader, it.thumbnailUrl, it.format) },
                    onBrowse = { tab = PulseTab.Home },
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (playerUi.hasCurrent) {
                MiniPlayer(playerUi, onTap = { nowPlaying = true }, onPlayPause = { playerVm.togglePlay() }, onNext = { playerVm.next() })
            }
            BottomBar(tab) { tab = it }
        }

        if (nowPlaying && playerUi.hasCurrent) {
            NowPlayingScreen(
                ui = playerUi,
                onClose = { nowPlaying = false },
                onPlayPause = { playerVm.togglePlay() },
                onNext = { playerVm.next() },
                onPrev = { playerVm.prev() },
                onSeek = { playerVm.seekTo(it) },
                onDownload = { showPicker = true },
            )
        }

        if (showSettings) {
            Box(Modifier.fillMaxSize().background(Bg0)) {
                SettingsScreen(
                    s = settings,
                    completedDownloads = downloads.count { it.status == DlStatus.Completed },
                    onBack = { showSettings = false },
                    onSetVideo = { settingsVm.setVideo(it) },
                    onSetWifi = { settingsVm.setWifiOnly(it) },
                    onSetPreferVideo = { settingsVm.setPreferVideo(it) },
                    onSetMax = { settingsVm.setMaxConcurrent(it) },
                )
            }
        }

        if (showPicker) {
            DownloadPicker(
                onDismiss = { showPicker = false },
                onPick = { fmt ->
                    playerUi.current?.let { c -> DownloadManager.enqueue(context, c.url, c.title, c.uploader, c.thumbnailUrl, fmt) }
                    showPicker = false
                    Toast.makeText(context, "Download started — see the Downloads tab", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun DownloadPicker(onDismiss: () -> Unit, onPick: (DlFormat) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onDismiss() })
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Bg2).navigationBarsPadding().padding(20.dp),
        ) {
            Text("Download", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(10.dp))
            PickerRow(DlFormat.MP4, "Best quality video, larger file", recommended = true, onPick)
            PickerRow(DlFormat.M4A, "Audio only, smaller file", recommended = false, onPick)
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
