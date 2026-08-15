package app.pulse.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.HomeCard
import app.pulse.core.HomeShelf
import app.pulse.core.StreamItem
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.ShimmerHost
import app.pulse.ui.components.SkeletonShelf
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx3

/** Which of the four page states the crossfade is showing. */
private enum class CategoryPage { Loading, Content, Empty, Error }

/** One "Moods & genres" category page — YT Music's own curated (personalized when signed in) shelves
 *  for the tapped tile, rendered HomeScreen-style. HomeScreen's Shelf/Card composables are private to
 *  that file, so this carries minimal equivalents (horizontal card rows per shelf). */
@Composable
fun CategoryScreen(
    title: String,
    shelves: List<HomeShelf>?,
    loading: Boolean,
    error: Boolean,
    currentUrl: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onOpenDetail: (String) -> Unit,
    onBrowse: (String) -> Unit,
) {
    // Root tap consumer: header dead zones and the loading/empty/error panes would otherwise let
    // taps fall through to the tab content behind during the AnimatedContent transition window.
    Column(Modifier.fillMaxSize().background(Bg0).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(20.dp))
            }
            Text(
                title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp, end = 16.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            val current = shelves
            val page = when {
                error -> CategoryPage.Error
                loading || current == null -> CategoryPage.Loading
                current.isEmpty() -> CategoryPage.Empty
                else -> CategoryPage.Content
            }
            Crossfade(targetState = page, animationSpec = AnimSpecs.normal(), label = "categoryPage") { p ->
                when (p) {
                    CategoryPage.Error -> Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            // categoryPage() threw (offline / rate-limited) — a real dead-end with Retry,
                            // matching HomeScreen's error idiom, NOT a silent fall-back to a plain search.
                            Text("Couldn't load $title — check your connection.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.size(12.dp))
                            Text("Retry", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                    CategoryPage.Loading -> CategorySkeleton()
                    CategoryPage.Empty -> Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.Center).padding(32.dp)) {
                            Text("Nothing here right now.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                    CategoryPage.Content ->
                        // current can only be null in this branch for the moment it fades out after the
                        // data was cleared; render an empty page rather than a second ShimmerHost.
                        if (current == null) Box(Modifier.fillMaxSize()) else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 150.dp)) {
                                itemsIndexed(current, key = { i, _ -> "catshelf_$i" }) { _, shelf -> CategoryShelf(shelf, currentUrl, onPlay, onOpenDetail, onBrowse) }
                            }
                        }
                }
            }
        }
    }
}

/** Skeleton shown while categoryPage() is in flight — mirrors CategoryShelf's 24.dp shelf rhythm. */
@Composable
private fun CategorySkeleton() {
    ShimmerHost(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
            repeat(3) {
                SkeletonShelf(Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun CategoryShelf(shelf: HomeShelf, currentUrl: String?, onPlay: (List<StreamItem>, Int) -> Unit, onOpenDetail: (String) -> Unit, onBrowse: (String) -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(shelf.title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, modifier = Modifier.padding(start = 16.dp, bottom = 14.dp))
        val playables = shelf.cards.filter { it.playable }.map { it.toStreamItem() }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelf.cards, key = { i, _ -> i }) { _, card ->
                CategoryCard(card, currentUrl) {
                    // Mirror HomeScreen's card dispatch: tracks play, browseId-bearing cards open the
                    // existing DetailScreen flow, anything else falls back to a title search.
                    if (card.playable) onPlay(playables, playables.indexOfFirst { it.url == card.toStreamItem().url }.coerceAtLeast(0))
                    else if (card.browseId != null) onOpenDetail(card.browseId) else onBrowse(card.title)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(card: HomeCard, currentUrl: String?, onClick: () -> Unit) {
    val isCurrent = card.playable && currentUrl != null && card.toStreamItem().url == currentUrl
    Column(Modifier.width(150.dp).clickable { onClick() }) {
        Box(Modifier.size(150.dp)) {
            Thumb(card.thumbnailUrl, Modifier.size(150.dp), 8.dp)
            if (isCurrent) {
                Box(Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Red, RoundedCornerShape(8.dp)))
                Box(Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Red).align(Alignment.BottomEnd), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = OnRed, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(card.title, color = Tx0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = Tx1, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
