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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.BrowseResult
import app.pulse.core.StreamItem
import app.pulse.core.secToClock
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.PlayingBars
import app.pulse.ui.components.ShimmerHost
import app.pulse.ui.components.SkeletonBox
import app.pulse.ui.components.SkeletonRow
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.RedW06
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Which of the three page states the crossfade is showing. */
private enum class DetailPage { Loading, Content, Error }

@Composable
fun DetailScreen(
    result: BrowseResult?,
    loading: Boolean,
    error: Boolean,
    currentUrl: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onShuffle: (List<StreamItem>) -> Unit,
    onDownloadAll: (List<StreamItem>, Boolean) -> Unit,
    onDownloadTrack: (StreamItem, Boolean) -> Unit,
    onMore: () -> Unit,
) {
    var audioOnly by remember { mutableStateOf(false) }
    // Root tap consumer: the loading/error panes and header dead zones would otherwise let taps
    // fall through to the layer behind (tab/category content during the AnimatedContent window).
    Box(Modifier.fillMaxSize().background(Bg0).pointerInput(Unit) { detectTapGestures { } }) {
        val res = result
        val page = when {
            error -> DetailPage.Error
            loading || res == null -> DetailPage.Loading
            else -> DetailPage.Content
        }
        Crossfade(targetState = page, animationSpec = AnimSpecs.normal(), label = "detailPage") { p ->
            when (p) {
                DetailPage.Error -> Box(Modifier.fillMaxSize()) {
                    // browse() failed (offline / rate-limited): a real dead-end state, distinct from the
                    // skeleton — this page used to spin forever with no message and no way to retry.
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load this page — check your connection.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("Retry", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
                DetailPage.Loading -> DetailSkeleton()
                DetailPage.Content ->
                    // res can only be null in this branch for the moment it fades out after the data
                    // was cleared; render an empty page rather than a second ShimmerHost.
                    if (res == null) Box(Modifier.fillMaxSize()) else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp)) {
                            item {
                                Box(Modifier.fillMaxWidth().height(360.dp)) {
                                    val ctx = LocalContext.current
                                    val backdrop = remember(res.thumbnailUrl) {
                                        ImageRequest.Builder(ctx).data(res.thumbnailUrl).crossfade(200).build()
                                    }
                                    AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(60.dp))
                                    Box(Modifier.matchParentSize().background(Color(0xCC000000)))
                                    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Thumb(res.thumbnailUrl, Modifier.size(176.dp), 12.dp)
                                        Spacer(Modifier.height(14.dp))
                                        Text(res.title, color = Tx0, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                                        if (res.subtitle.isNotBlank()) Text(res.subtitle, color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
                                        Spacer(Modifier.height(16.dp))
                                        // action bar — Download · format · shuffle · play
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(Bg3).clickable { if (res.tracks.isNotEmpty()) onDownloadAll(res.tracks, audioOnly) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.Download, null, tint = Tx0, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Download", color = Tx0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Text(
                                                if (audioOnly) "M4A" else "MP4", color = Tx0, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Bg3).clickable { audioOnly = !audioOnly }.padding(horizontal = 14.dp, vertical = 10.dp),
                                            )
                                            Box(Modifier.size(44.dp).clip(CircleShape).background(Bg3).clickable { if (res.tracks.isNotEmpty()) onShuffle(res.tracks) }, contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.Shuffle, "shuffle", tint = Tx0, modifier = Modifier.size(20.dp))
                                            }
                                            Box(Modifier.size(56.dp).clip(CircleShape).background(Red).clickable { if (res.tracks.isNotEmpty()) onPlay(res.tracks, 0) }, contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.PlayArrow, "play", tint = OnRed, modifier = Modifier.size(28.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                    }
                                }
                            }
                            itemsIndexed(res.tracks, key = { i, t -> t.url + i }) { i, t ->
                                TrackRow(i + 1, t, t.url == currentUrl, isPlaying, { onPlay(res.tracks, i) }, { onDownloadTrack(t, audioOnly) })
                            }
                        }
                    }
            }
        }

        // circular back (top-left) + overflow (top-right)
        Box(Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0x8C000000)).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0x8C000000)).clickable { onMore() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreHoriz, "more", tint = Tx0, modifier = Modifier.size(20.dp))
        }
    }
}

/** Skeleton page shown while browse() is in flight — mirrors the real header + track list. */
@Composable
private fun DetailSkeleton() {
    ShimmerHost(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonBox(Modifier.size(176.dp), RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(18.dp))
                    SkeletonBox(Modifier.width(210.dp).height(18.dp), RoundedCornerShape(5.dp))
                    Spacer(Modifier.height(10.dp))
                    SkeletonBox(Modifier.width(140.dp).height(12.dp), RoundedCornerShape(4.dp))
                    Spacer(Modifier.height(20.dp))
                    // action-bar stubs, sized like the real pills/buttons so the fade-in doesn't jump
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBox(Modifier.width(118.dp).height(38.dp), RoundedCornerShape(99.dp))
                        SkeletonBox(Modifier.width(54.dp).height(38.dp), RoundedCornerShape(99.dp))
                        SkeletonBox(Modifier.size(44.dp), CircleShape)
                        SkeletonBox(Modifier.size(56.dp), CircleShape)
                    }
                }
            }
            repeat(8) { SkeletonRow() }
        }
    }
}

@Composable
private fun TrackRow(num: Int, t: StreamItem, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit, onDownload: () -> Unit) {
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
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(34.dp).clickable { onDownload() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, "download", tint = Tx2, modifier = Modifier.size(18.dp))
        }
        if (t.durationSec > 0) {
            Spacer(Modifier.width(4.dp))
            Text(t.durationSec.secToClock(), color = Tx2, fontSize = 11.sp)
        }
    }
}
