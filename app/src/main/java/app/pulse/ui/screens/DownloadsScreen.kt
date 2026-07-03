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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.download.DlStatus
import app.pulse.download.DownloadItem
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun DownloadsScreen(
    title: String,
    items: List<DownloadItem>,
    onlyCompleted: Boolean,
    onPlay: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
) {
    val shown = if (onlyCompleted) items.filter { it.status == DlStatus.Completed } else items
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(title, color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (onlyCompleted) "No downloads yet." else "No downloads yet.\nTap the download icon while a track is playing.",
                    color = Tx3, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 150.dp)) {
                items(shown, key = { it.id }) { dl -> DownloadRow(dl, onPlay, onRemove) }
            }
        }
    }
}

@Composable
private fun DownloadRow(dl: DownloadItem, onPlay: (DownloadItem) -> Unit, onRemove: (DownloadItem) -> Unit) {
    val playable = dl.status == DlStatus.Completed
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = playable) { onPlay(dl) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Thumb(dl.thumbnailUrl, Modifier.size(48.dp), 8.dp)
                if (playable) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(dl.title, color = Tx0, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${dl.uploader} · ${dl.format.label}", color = Tx1, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when (dl.status) {
                DlStatus.Downloading -> Text("${(dl.progress * 100).toInt()}%", color = Tx0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                DlStatus.Queued -> Text("Queued", color = Tx2, fontSize = 11.sp)
                DlStatus.Completed -> Icon(Icons.Rounded.CheckCircle, "downloaded", tint = Tx2, modifier = Modifier.size(18.dp))
                DlStatus.Failed -> Icon(Icons.Rounded.ErrorOutline, "failed", tint = Tx2, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.size(40.dp).clickable { onRemove(dl) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "remove", tint = Tx2, modifier = Modifier.size(18.dp))
            }
        }
        if (dl.status == DlStatus.Downloading) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp).clip(RoundedCornerShape(2.dp)).background(Line08)) {
                Box(Modifier.fillMaxWidth(dl.progress).height(2.dp).clip(RoundedCornerShape(2.dp)).background(Red))
            }
        }
    }
}
