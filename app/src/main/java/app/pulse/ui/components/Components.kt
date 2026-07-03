package app.pulse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.StreamItem
import app.pulse.core.secToClock
import app.pulse.playback.PlayerUi
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.Nav
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.RedW06
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx4
import coil.compose.AsyncImage

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Bg3), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Tx4, modifier = Modifier.size(20.dp))
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun PlayingBars(modifier: Modifier = Modifier, playing: Boolean = true, color: Color = Red) {
    // NOTE: the composable animation calls MUST run unconditionally on every recomposition.
    // Calling them inside only one branch of an `if (playing)` changes the group count when
    // `playing` flips (pause/resume) and corrupts Compose's slot table (Stack.pop crash).
    val t = rememberInfiniteTransition(label = "eq")
    val h1 by t.animateFloat(0.35f, if (playing) 1f else 0.4f, infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b1")
    val h2 by t.animateFloat(0.35f, if (playing) 1f else 0.4f, infiniteRepeatable(tween(220), RepeatMode.Reverse), label = "b2")
    val h3 by t.animateFloat(0.35f, if (playing) 1f else 0.4f, infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "b3")
    Row(modifier.height(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(h1, h2, h3).forEach { hv ->
            Box(Modifier.width(3.dp).fillMaxHeight(hv).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun StreamRow(item: StreamItem, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (isCurrent) RedW06 else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Thumb(item.thumbnailUrl, Modifier.size(48.dp), 8.dp)
            if (isCurrent) {
                Box(Modifier.matchParentSize().background(Color(0xB3000000), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    PlayingBars(playing = isPlaying)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = if (isCurrent) Red else Tx0, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.uploader, color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(item.durationSec.secToClock(), color = Tx2, fontSize = 11.sp)
    }
}

@Composable
fun MiniPlayer(ui: PlayerUi, onTap: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val item = ui.current ?: return
    val progress = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth().background(Bg2)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Line08)) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Red))
        }
        Row(Modifier.fillMaxWidth().height(62.dp).clickable { onTap() }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item.thumbnailUrl, Modifier.size(46.dp), 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Tx0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.uploader, color = Tx1, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(48.dp).clickable(enabled = !ui.loading) { onPlayPause() }, contentAlignment = Alignment.Center) {
                if (ui.loading) CircularProgressIndicator(color = Red, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "play/pause", tint = Red, modifier = Modifier.size(26.dp))
            }
            Box(Modifier.size(48.dp).clickable { onNext() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SkipNext, "next", tint = Tx0, modifier = Modifier.size(24.dp))
            }
        }
    }
}

enum class PulseTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Search("Search", Icons.Rounded.Search),
    Library("Library", Icons.Rounded.LibraryMusic),
    Downloads("Downloads", Icons.Rounded.Download),
}

@Composable
fun BottomBar(current: PulseTab, modifier: Modifier = Modifier, onSelect: (PulseTab) -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Nav).navigationBarsPadding().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseTab.entries.forEach { tab ->
            val active = tab == current
            Column(Modifier.clickable { onSelect(tab) }.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(tab.icon, tab.label, tint = if (active) Red else Tx2, modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, color = if (active) Red else Tx2, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
