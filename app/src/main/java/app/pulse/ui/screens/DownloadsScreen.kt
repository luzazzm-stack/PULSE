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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
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
import app.pulse.core.StreamItem
import app.pulse.core.secToClock
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
    localTracks: List<StreamItem>,
    hasAudioPermission: Boolean,
    scanningLocal: Boolean,
    onRequestPermission: () -> Unit,
    onRescanLocal: () -> Unit,
    onPlayLocal: (Int) -> Unit,
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

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
            if (items.isNotEmpty()) {
                item {
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg1).border(1.dp, Line08, RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Download, null, tint = Tx1, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("${completed.size} downloaded · ${downloading.size + queued.size} in queue", color = Tx0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                section("DOWNLOADING (${downloading.size})", downloading) { DlRow(it, onPlay, onRemove, onRetry) }
                section("QUEUED (${queued.size})", queued) { DlRow(it, onPlay, onRemove, onRetry) }
                section("COMPLETED (${completed.size})", completed) { DlRow(it, onPlay, onRemove, onRetry) }
                section("FAILED (${failed.size})", failed) { DlRow(it, onPlay, onRemove, onRetry) }
            } else {
                item { NoDownloadsHint(onBrowse) }
            }

            // ── On this device ──────────────────────────────────────────────
            item {
                DeviceHeader(
                    count = if (hasAudioPermission) localTracks.size else null,
                    scanning = scanningLocal,
                    action = if (hasAudioPermission) "Rescan" else "Allow access",
                    onAction = { if (hasAudioPermission) onRescanLocal() else onRequestPermission() },
                )
            }
            when {
                !hasAudioPermission -> item { DeviceHint("Let Emma read your device library to find music you already downloaded elsewhere.") }
                scanningLocal && localTracks.isEmpty() -> item { DeviceHint("Scanning your device…") }
                localTracks.isEmpty() -> item { DeviceHint("No music files found on this device.") }
                else -> items(localTracks.size, key = { "local_${localTracks[it].url}" }) { i ->
                    LocalRow(localTracks[i]) { onPlayLocal(i) }
                }
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
private fun DeviceHeader(count: Int?, scanning: Boolean, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Smartphone, null, tint = Tx2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            "ON THIS DEVICE" + (count?.let { " ($it)" } ?: ""),
            color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(99.dp)).background(Bg1).clickable { onAction() }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = Tx1, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (scanning) "Scanning…" else action, color = Tx0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DeviceHint(text: String) {
    Text(text, color = Tx3, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), lineHeight = 17.sp)
}

@Composable
private fun LocalRow(track: StreamItem, onPlay: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg1).clickable { onPlay() }) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Thumb(track.thumbnailUrl, Modifier.size(48.dp), 8.dp)
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.uploader, color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (track.durationSec > 0) Text(track.durationSec.secToClock(), color = Tx3, fontSize = 11.sp)
        }
    }
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
private fun NoDownloadsHint(onBrowse: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Bg1), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Tx3, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Nothing downloaded yet", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Play a track, then tap the download icon — or find music already on your device below.", color = Tx2, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        Text("Browse music", color = OnRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onBrowse() }.padding(horizontal = 22.dp, vertical = 10.dp))
    }
}
