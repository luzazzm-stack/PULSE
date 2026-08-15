package app.pulse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.pulse.core.msToClock
import app.pulse.playback.PlayerUi
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Line20
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx4
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** No-overshoot settle for freshly-changed artwork (0.97f -> 1f). */
private val ArtSettleSpring: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Slight-overshoot pop shared by the like heart, play/pause, and toggle icons. */
private val PopSpring: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun NowPlayingScreen(
    ui: PlayerUi,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onDownload: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    player: Player?,
    onToggleVideo: () -> Unit,
    source: String,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    // Keep the last non-null track so the screen stays rendered through its exit animation
    // after playback is cleared (mirrors the MiniPlayer pattern). Only the overlay's short
    // exit window ever reads the cached value.
    var lastItem by remember { mutableStateOf(ui.current) }
    SideEffect { if (ui.current != null) lastItem = ui.current }
    val item = ui.current ?: lastItem
    // The degenerate exit-window box must still shield what's behind it — the dying overlay
    // remains hit-testable through its slide-out, same as the full screen below.
    if (item == null) { Box(Modifier.fillMaxSize().background(Bg0).pointerInput(Unit) { detectTapGestures { } }); return }

    // ---- Motion state: one settle Animatable + a handful of single-spring/tween states. ----
    val context = LocalContext.current
    val artRequest = remember(item.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(item.thumbnailUrl)
            .crossfade(AnimSpecs.NormalMs)
            .build()
    }
    val artScale = remember { Animatable(1f) }
    LaunchedEffect(item.thumbnailUrl) {
        artScale.snapTo(0.97f)
        artScale.animateTo(1f, ArtSettleSpring)
    }
    val likeScale = remember { Animatable(1f) }
    var likePopArmed by remember { mutableStateOf(false) }
    // The pop is feedback for a TAP only: a skip can also flip `liked` (liked -> unliked track),
    // which must settle silently — likePopUrl tracks the item so a track change never pops.
    var likePopUrl by remember { mutableStateOf(item.url) }
    LaunchedEffect(liked, item.url) {
        if (item.url != likePopUrl) {
            likePopUrl = item.url // `liked` changed because the TRACK changed — no pop
        } else if (likePopArmed) {
            likeScale.snapTo(if (liked) 0.6f else 0.85f)
            likeScale.animateTo(1f, PopSpring)
        } // else: no pop for the state the screen opened with
        likePopArmed = true
    }
    val playScale by animateFloatAsState(
        targetValue = if (ui.isPlaying) 1f else 0.94f,
        animationSpec = PopSpring,
        label = "playScale",
    )
    val tintSpec = remember { AnimSpecs.fast<Color>() } // this screen recomposes each position tick; allocate the tween once
    val shuffleTint by animateColorAsState(if (ui.shuffle) Red else Tx1, tintSpec, label = "shuffleTint")
    val shuffleScale by animateFloatAsState(if (ui.shuffle) 1.12f else 1f, PopSpring, label = "shuffleScale")
    val repeatTint by animateColorAsState(if (ui.repeat > 0) Red else Tx1, tintSpec, label = "repeatTint")
    val repeatScale by animateFloatAsState(if (ui.repeat > 0) 1.12f else 1f, PopSpring, label = "repeatScale")

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var scrubDur by remember { mutableLongStateOf(0L) }
    val progress = when {
        scrubbing -> scrubValue
        ui.durationMs > 0 -> (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownPos = if (scrubbing) (scrubValue * scrubDur).toLong() else ui.positionMs

    // Root tap consumer: dead areas (chrome, spacers, padding) must not let taps fall through to
    // whatever is stacked behind this overlay. Children are hit-tested first, so every control
    // above keeps working — the root only swallows taps nobody else claimed.
    Box(Modifier.fillMaxSize().background(Bg0).pointerInput(Unit) { detectTapGestures { } }) {
        AsyncImage(model = artRequest, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(55.dp))
        Box(Modifier.matchParentSize().background(Color(0xD6000000)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "close", tint = Tx0, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", color = Tx2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    Text(source, color = Tx0, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(44.dp).clickable { onMore() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, "more", tint = Tx0, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            if (ui.videoMode && player != null) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; setPlayer(player) } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
                )
            } else {
                // Inline Thumb-alike so the artwork can crossfade (250 ms) and settle in scale
                // on track change. graphicsLayer reads artScale in the layer block only, so the
                // spring never recomposes this subtree.
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .aspectRatio(1f)
                        .graphicsLayer { scaleX = artScale.value; scaleY = artScale.value }
                        .clip(RoundedCornerShape(16.dp))
                        .background(Bg3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Tx4, modifier = Modifier.size(20.dp))
                    AsyncImage(
                        model = artRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.clip(RoundedCornerShape(99.dp)).background(Bg1).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ModePill("Audio", !ui.videoMode) { if (ui.videoMode) onToggleVideo() }
                ModePill("Video", ui.videoMode) { if (!ui.videoMode) onToggleVideo() }
            }
            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.uploader, color = Tx1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(44.dp).clickable { onToggleLike() }, contentAlignment = Alignment.Center) {
                    Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "like", tint = if (liked) Red else Tx1,
                        modifier = Modifier.size(26.dp).graphicsLayer { scaleX = likeScale.value; scaleY = likeScale.value })
                }
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
                Box(Modifier.size(48.dp).clickable { onShuffle() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Shuffle, "shuffle", tint = shuffleTint,
                        modifier = Modifier.size(24.dp).graphicsLayer { scaleX = shuffleScale; scaleY = shuffleScale })
                }
                Box(Modifier.size(52.dp).clickable { onPrev() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipPrevious, "previous", tint = Tx0, modifier = Modifier.size(36.dp))
                }
                Box(Modifier.size(72.dp).graphicsLayer { scaleX = playScale; scaleY = playScale }.clip(CircleShape).background(Red).clickable(enabled = !ui.loading) { onPlayPause() }, contentAlignment = Alignment.Center) {
                    if (ui.loading) CircularProgressIndicator(color = OnRed, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                    else Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "play/pause", tint = OnRed, modifier = Modifier.size(36.dp))
                }
                Box(Modifier.size(52.dp).clickable { onNext() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipNext, "next", tint = Tx0, modifier = Modifier.size(36.dp))
                }
                Box(Modifier.size(48.dp).clickable { onRepeat() }, contentAlignment = Alignment.Center) {
                    Icon(if (ui.repeat == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "repeat",
                        tint = repeatTint,
                        modifier = Modifier.size(24.dp).graphicsLayer { scaleX = repeatScale; scaleY = repeatScale })
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clickable { onLyrics() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Article, "lyrics", tint = Tx1, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.size(48.dp).clickable { onDownload() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Download, "download", tint = Tx1, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.size(48.dp).clickable { onQueue() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.QueueMusic, "queue", tint = Tx1, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (active) OnRed else Tx1, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (active) Red else Color.Transparent).clickable { onClick() }.padding(horizontal = 20.dp, vertical = 7.dp),
    )
}
