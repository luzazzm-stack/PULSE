package app.pulse.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.SearchViewModel
import app.pulse.core.StreamItem
import app.pulse.playback.PlayerViewModel
import app.pulse.ui.components.BottomBar
import app.pulse.ui.components.MiniPlayer
import app.pulse.ui.components.PulseTab
import app.pulse.ui.screens.NowPlayingScreen
import app.pulse.ui.screens.SearchScreen
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2

@Composable
fun PulseRoot(searchVm: SearchViewModel, playerVm: PlayerViewModel) {
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val playerUi by playerVm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(PulseTab.Search) }
    var nowPlaying by remember { mutableStateOf(false) }

    val play = remember(playerVm) { { list: List<StreamItem>, i: Int -> playerVm.playList(list, i) } }
    val onDownload = remember(context) { { Toast.makeText(context, "Downloads land in the next PULSE update", Toast.LENGTH_SHORT).show() } }

    if (nowPlaying) BackHandler { nowPlaying = false }
    val curUrl = playerUi.current?.url

    Box(Modifier.fillMaxSize().background(Bg0)) {
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                PulseTab.Search -> SearchScreen(searchState, curUrl, playerUi.isPlaying, searchVm::setQuery, searchVm::search, play)
                PulseTab.Home -> Placeholder("Home", "Browse & recommendations arrive in the next update.")
                PulseTab.Library -> Placeholder("Library", "Your library lands with downloads.")
                PulseTab.Downloads -> Placeholder("Downloads", "The MP4 / M4A download engine is the next update.")
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
                onDownload = onDownload,
            )
        }
    }
}

@Composable
private fun Placeholder(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(title, color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Tx2, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
