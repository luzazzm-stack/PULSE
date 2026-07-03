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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.HomeState
import app.pulse.core.HomeCard
import app.pulse.core.HomeShelf
import app.pulse.core.StreamItem
import app.pulse.ui.components.PlayingBars
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

private val CHIPS = listOf("All", "Music", "Podcasts", "Downloaded")

@Composable
fun HomeScreen(
    state: HomeState,
    currentUrl: String?,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onBrowse: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    var selectedChip by remember { mutableStateOf(0) }
    val greeting = remember {
        when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // header — greeting + notifications / history / settings
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(greeting, color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.NotificationsNone, "notifications", tint = Tx1, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.History, "history", tint = Tx1, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(30.dp).clickable { onSettings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, "settings", tint = Tx1, modifier = Modifier.size(24.dp))
            }
        }
        // chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CHIPS.forEachIndexed { i, c ->
                Chip(c, active = i == selectedChip) { if (i == 3) onOpenDownloads() else selectedChip = i }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                state.error != null && state.shelves.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load home.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(12.dp))
                    Text("Retry", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                selectedChip == 2 -> Box(Modifier.align(Alignment.Center).padding(32.dp)) {
                    Text("No podcasts yet — PULSE is all music for now.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                else -> {
                    val quick = remember_quick(state.shelves)
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 150.dp)) {
                        if (quick.isNotEmpty()) item(key = "quickpicks") { QuickPicks(quick, currentUrl, onPlay, onOpenDetail, onBrowse) }
                        itemsIndexed(state.shelves, key = { i, _ -> "shelf_$i" }) { _, shelf -> Shelf(shelf, currentUrl, onPlay, onBrowse, onOpenDetail) }
                    }
                }
            }
        }
    }
}

private fun remember_quick(shelves: List<HomeShelf>): List<HomeCard> =
    shelves.flatMap { it.cards }.distinctBy { it.videoId ?: it.browseId ?: it.title }.take(8)

@Composable
private fun QuickPicks(cards: List<HomeCard>, currentUrl: String?, onPlay: (List<StreamItem>, Int) -> Unit, onOpenDetail: (String) -> Unit, onBrowse: (String) -> Unit) {
    val playables = cards.filter { it.playable }.map { it.toStreamItem() }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { card ->
                    QuickTile(card, currentUrl, Modifier.weight(1f)) {
                        if (card.playable) onPlay(playables, playables.indexOfFirst { it.url == card.toStreamItem().url }.coerceAtLeast(0))
                        else if (card.browseId != null) onOpenDetail(card.browseId) else onBrowse(card.title)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickTile(card: HomeCard, currentUrl: String?, modifier: Modifier, onClick: () -> Unit) {
    val isCurrent = card.playable && currentUrl != null && card.toStreamItem().url == currentUrl
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(8.dp)).background(Bg3).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(card.thumbnailUrl, Modifier.size(56.dp), 0.dp)
        Text(card.title, color = Tx0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (isCurrent) {
            PlayingBars(playing = true)
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun Shelf(shelf: HomeShelf, currentUrl: String?, onPlay: (List<StreamItem>, Int) -> Unit, onBrowse: (String) -> Unit, onOpenDetail: (String) -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(shelf.title, color = Tx0, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, modifier = Modifier.padding(start = 16.dp, bottom = 14.dp))
        val playables = shelf.cards.filter { it.playable }.map { it.toStreamItem() }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelf.cards, key = { i, _ -> i }) { _, card ->
                Card(card, currentUrl) {
                    if (card.playable) onPlay(playables, playables.indexOfFirst { it.url == card.toStreamItem().url }.coerceAtLeast(0))
                    else if (card.browseId != null) onOpenDetail(card.browseId) else onBrowse(card.title)
                }
            }
        }
    }
}

@Composable
private fun Card(card: HomeCard, currentUrl: String?, onClick: () -> Unit) {
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

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (active) OnRed else Tx0, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(99.dp)).background(if (active) Red else Bg3).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
