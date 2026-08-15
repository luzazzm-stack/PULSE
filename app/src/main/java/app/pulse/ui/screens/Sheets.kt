package app.pulse.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.StreamItem
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.PlayingBars
import app.pulse.ui.components.ShimmerHost
import app.pulse.ui.components.SkeletonLines
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.RedW06
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun QueueSheet(items: List<StreamItem>, currentIndex: Int, isPlaying: Boolean, onDismiss: () -> Unit, onPlayIndex: (Int) -> Unit) {
    // Keep the last non-empty queue so the rows stay drawn through the sheet's 150 ms exit after
    // playback is cleared from the notification (items empties in the same snapshot write that
    // starts the exit) — mirrors the MiniPlayer cache; dies with the overlay's composition.
    var lastItems by remember { mutableStateOf(items) }
    SideEffect { if (items.isNotEmpty()) lastItems = items }
    val shown = if (items.isNotEmpty()) items else lastItems
    Column(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onDismiss() })
        // Panel-level tap consumer: taps on panel dead areas (header, padding) must neither reach
        // the scrim's dismiss nor pass through the overlay; row clickables are children and win.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Bg1).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding()) {
            Text("Up next", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            // No animateItemPlacement here: keys are positional (url + i, urls can repeat) and the
            // only queue mutation is append, so a placement move can never fire — the modifier
            // would be pure per-row animation bookkeeping on J7-class devices.
            LazyColumn(Modifier.heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(shown, key = { i, t -> t.url + i }) { i, t ->
                    val current = i == currentIndex
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).background(if (current) RedW06 else Color.Transparent).clickable { onPlayIndex(i) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Thumb(t.thumbnailUrl, Modifier.size(44.dp), 6.dp)
                            if (current) Box(Modifier.size(44.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { PlayingBars(playing = isPlaying) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = if (current) Red else Tx0, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.uploader, color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsScreen(title: String, artist: String, lyrics: String?, loading: Boolean, onClose: () -> Unit) {
    // Keep the last non-blank title so the header doesn't collapse to "" during the overlay's
    // exit after playback is cleared (current -> null mid-exit) — same pattern as QueueSheet above.
    var lastTitle by remember { mutableStateOf(title) }
    SideEffect { if (title.isNotBlank()) lastTitle = title }
    val shownTitle = if (title.isNotBlank()) title else lastTitle
    // Root tap consumer: header dead areas and the empty/loading panes must not let taps fall
    // through to the NowPlaying screen (or anything else) stacked behind this overlay.
    Column(Modifier.fillMaxSize().background(Bg0).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, "close", tint = Tx0, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("LYRICS", color = Tx2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Text(shownTitle, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxSize()) {
            // Tiny state key (0 loading / 1 empty / 2 lyrics) so the three panes fade into each other.
            val pane = if (loading) 0 else if (lyrics.isNullOrBlank()) 1 else 2
            Crossfade(pane, animationSpec = AnimSpecs.fast(), label = "lyricsPane") { p ->
                when (p) {
                    0 -> ShimmerHost(Modifier.fillMaxSize()) {
                        SkeletonLines(12, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp))
                    }
                    1 -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Lyrics not available for this track.", color = Tx3, fontSize = 13.sp) }
                    else -> Text(lyrics.orEmpty(), color = Tx0, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 150.dp))
                }
            }
        }
    }
}
