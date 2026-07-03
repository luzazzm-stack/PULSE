package app.pulse.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.Playlist
import app.pulse.core.StreamItem
import app.pulse.download.DlStatus
import app.pulse.download.DownloadItem
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun LibraryScreen(
    downloads: List<DownloadItem>,
    playlists: List<Playlist>,
    onPlay: (DownloadItem) -> Unit,
    onPlayPlaylist: (List<StreamItem>) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val completed = downloads.filter { it.status == DlStatus.Completed }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", color = Tx0, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Add, null, tint = Tx1, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Rounded.Sort, null, tint = Tx1, modifier = Modifier.size(24.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Downloaded", true)
            Chip("Playlists", false)
            Chip("Albums", false)
            Chip("Artists", false)
        }
        Spacer(Modifier.height(12.dp))

        // pinned Downloaded card
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(Bg1).border(1.dp, Line08, RoundedCornerShape(12.dp)).clickable { onOpenDownloads() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Bg2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Download, null, tint = Tx1, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Downloaded", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${completed.size} songs · available offline", color = Tx2, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Tx3, modifier = Modifier.size(20.dp))
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp)) {
            if (playlists.isNotEmpty()) {
                item { SectionLabel("PLAYLISTS") }
                items(playlists, key = { "pl_" + it.id }) { p -> PlaylistRow(p) { if (p.tracks.isNotEmpty()) onPlayPlaylist(p.tracks) } }
            }
            if (completed.isNotEmpty()) {
                item { SectionLabel("DOWNLOADED") }
                items(completed, key = { it.id }) { dl -> LibRow(dl, onPlay) }
            }
            if (playlists.isEmpty() && completed.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Download songs or add tracks to a playlist to fill your library.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun PlaylistRow(p: Playlist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Bg2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.QueueMusic, null, tint = Tx2, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Playlist · ${p.tracks.size} songs", color = Tx2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean) {
    Text(
        label, color = if (active) Color.Black else Tx1, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(99.dp)).background(if (active) Color.White else Bg3).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun LibRow(dl: DownloadItem, onPlay: (DownloadItem) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).clickable { onPlay(dl) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Thumb(dl.thumbnailUrl, Modifier.size(48.dp), 8.dp)
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dl.title, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${dl.uploader} · Downloaded", color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
