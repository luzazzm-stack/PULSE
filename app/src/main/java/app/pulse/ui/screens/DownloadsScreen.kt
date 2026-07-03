package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import app.pulse.download.DlStatus
import app.pulse.download.DownloadItem
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    onPlay: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onBrowse: () -> Unit,
) {
    val downloading = items.filter { it.status == DlStatus.Downloading }
    val queued = items.filter { it.status == DlStatus.Queued }
    val completed = items.filter { it.status == DlStatus.Completed }
    val failed = items.filter { it.status == DlStatus.Failed }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Downloads", color = Tx0, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 10.dp))

        if (items.isEmpty()) {
            EmptyDownloads(onBrowse)
        } else {
            // storage strip
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg1).border(1.dp, Line08, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Download, null, tint = Tx1, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("${completed.size} downloaded · ${downloading.size + queued.size} in queue", color = Tx0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
                section("DOWNLOADING (${downloading.size})", downloading) { DlRow(it, onPlay, onRemove, onRetry) }
                section("QUEUED (${queued.size})", queued) { DlRow(it, onPlay, onRemove, onRetry) }
                section("COMPLETED (${completed.size})", completed) { DlRow(it, onPlay, onRemove, onRetry) }
                section("FAILED (${failed.size})", failed) { DlRow(it, onPlay, onRemove, onRetry) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, list: List<DownloadItem>, row: @Composable (DownloadItem) -> Unit,
) {
    if (list.isEmpty()) return
    item { Text(title, color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)) }
    items(list.size, key = { list[it].id }) { row(list[it]) }
}

@Composable
private fun DlRow(dl: DownloadItem, onPlay: (DownloadItem) -> Unit, onRemove: (DownloadItem) -> Unit, onRetry: (DownloadItem) -> Unit) {
    val playable = dl.status == DlStatus.Completed
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg1).clickable(enabled = playable || dl.status == DlStatus.Failed) { if (playable) onPlay(dl) else onRetry(dl) }) {
        Row(Modifier.fillMaxWidth().height(if (dl.status == DlStatus.Downloading) 72.dp else 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Thumb(dl.thumbnailUrl, Modifier.size(48.dp), 8.dp)
                if (playable) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(dl.title, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${dl.uploader} · ${dl.format.label}", color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when (dl.status) {
                DlStatus.Downloading -> Text("${(dl.progress * 100).toInt()}%", color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                DlStatus.Queued -> Text("Queued", color = Tx3, fontSize = 11.sp)
                DlStatus.Completed -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(Bg3), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DownloadDone, null, tint = Tx1, modifier = Modifier.size(14.dp)) }
                DlStatus.Failed -> Icon(Icons.Rounded.Refresh, "retry", tint = Tx1, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.size(36.dp).clickable { onRemove(dl) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "remove", tint = Tx3, modifier = Modifier.size(16.dp))
            }
        }
        if (dl.status == DlStatus.Downloading) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(dl.progress).height(2.dp).background(Red))
        }
    }
}

@Composable
private fun EmptyDownloads(onBrowse: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Bg1), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, null, tint = Tx3, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Nothing downloaded yet", color = Tx0, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Play a track, then tap the download icon.\nDefault is Video (MP4) at max quality.", color = Tx2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        Text("Browse music", color = OnRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onBrowse() }.padding(horizontal = 22.dp, vertical = 10.dp))
    }
}
