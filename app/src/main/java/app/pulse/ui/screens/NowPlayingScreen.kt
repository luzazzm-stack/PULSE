package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.msToClock
import app.pulse.playback.PlayerUi
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Line20
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import coil.compose.AsyncImage

@Composable
fun NowPlayingScreen(
    ui: PlayerUi,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownload: () -> Unit,
) {
    val item = ui.current
    if (item == null) { Box(Modifier.fillMaxSize().background(Bg0)); return }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var scrubDur by remember { mutableLongStateOf(0L) }
    val progress = when {
        scrubbing -> scrubValue
        ui.durationMs > 0 -> (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownPos = if (scrubbing) (scrubValue * scrubDur).toLong() else ui.positionMs

    Box(Modifier.fillMaxSize().background(Bg0)) {
        AsyncImage(model = item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(55.dp))
        Box(Modifier.matchParentSize().background(Color(0xD6000000)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "close", tint = Tx0, modifier = Modifier.size(30.dp))
                }
                Text("NOW PLAYING", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Box(Modifier.size(44.dp).clickable { onDownload() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Download, "download", tint = Tx0, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            Thumb(item.thumbnailUrl, Modifier.fillMaxWidth(0.82f).aspectRatio(1f), 16.dp)
            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth()) {
                Text(item.title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.uploader, color = Tx1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(8.dp))
            Slider(
                value = progress,
                onValueChange = { if (!scrubbing) { scrubbing = true; scrubDur = ui.durationMs }; scrubValue = it },
                onValueChangeFinished = { onSeek((scrubValue * scrubDur).toLong()); scrubbing = false },
                colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Line20),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shownPos.msToClock(), color = Tx2, fontSize = 11.sp)
                Text(ui.durationMs.msToClock(), color = Tx2, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable { onPrev() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipPrevious, "previous", tint = Tx0, modifier = Modifier.size(36.dp))
                }
                Box(Modifier.size(72.dp).clip(CircleShape).background(Red).clickable(enabled = !ui.loading) { onPlayPause() }, contentAlignment = Alignment.Center) {
                    if (ui.loading) CircularProgressIndicator(color = OnRed, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                    else Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "play/pause", tint = OnRed, modifier = Modifier.size(36.dp))
                }
                Box(Modifier.size(52.dp).clickable { onNext() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipNext, "next", tint = Tx0, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
