package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.pulse.core.BrowseResult
import app.pulse.core.StreamItem
import app.pulse.ui.components.PlayingBars
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.RedW06
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import coil.compose.AsyncImage

@Composable
fun DetailScreen(
    result: BrowseResult?,
    loading: Boolean,
    currentUrl: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onShuffle: (List<StreamItem>) -> Unit,
    onDownloadAll: (List<StreamItem>) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Bg0)) {
        if (loading || result == null) {
            CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        AsyncImage(model = result.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(60.dp))
                        Box(Modifier.matchParentSize().background(Color(0xCC000000)))
                        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Thumb(result.thumbnailUrl, Modifier.size(176.dp), 12.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(result.title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                            if (result.subtitle.isNotBlank()) Text(result.subtitle, color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { if (result.tracks.isNotEmpty()) onPlay(result.tracks, 0) }.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = OnRed, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(Modifier.size(44.dp).clip(CircleShape).background(Bg3).clickable { if (result.tracks.isNotEmpty()) onShuffle(result.tracks) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Shuffle, "shuffle", tint = Tx0, modifier = Modifier.size(20.dp))
                                }
                                Box(Modifier.size(44.dp).clip(CircleShape).background(Bg3).clickable { if (result.tracks.isNotEmpty()) onDownloadAll(result.tracks) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Download, "download all", tint = Tx0, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                itemsIndexed(result.tracks, key = { i, t -> t.url + i }) { i, t ->
                    TrackRow(i + 1, t, t.url == currentUrl, isPlaying) { onPlay(result.tracks, i) }
                }
            }
        }

        Box(Modifier.statusBarsPadding().padding(4.dp).size(44.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TrackRow(num: Int, t: StreamItem, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(if (isCurrent) RedW06 else Color.Transparent).clickable { onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) PlayingBars(playing = isPlaying) else Text("$num", color = Tx2, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (isCurrent) Red else Tx0, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.uploader.isNotBlank()) Text(t.uploader, color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
