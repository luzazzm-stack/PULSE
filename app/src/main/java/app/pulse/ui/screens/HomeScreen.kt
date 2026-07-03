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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.HomeState
import app.pulse.core.HomeCard
import app.pulse.core.HomeShelf
import app.pulse.core.StreamItem
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun HomeScreen(
    state: HomeState,
    currentUrl: String?,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onBrowse: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("PULSE", color = Tx0, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(5.dp))
            Box(Modifier.padding(bottom = 4.dp).size(6.dp).clip(CircleShape).background(Red))
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                state.error != null && state.shelves.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load home.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(12.dp))
                    Text("Retry", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 150.dp)) {
                    items(state.shelves, key = { it.title + it.cards.size }) { shelf -> Shelf(shelf, currentUrl, onPlay, onBrowse) }
                }
            }
        }
    }
}

@Composable
private fun Shelf(shelf: HomeShelf, currentUrl: String?, onPlay: (List<StreamItem>, Int) -> Unit, onBrowse: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(shelf.title, color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        val playables = shelf.cards.filter { it.playable }.map { it.toStreamItem() }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shelf.cards, key = { it.title + (it.videoId ?: it.browseId ?: "") }) { card ->
                Card(card, currentUrl) {
                    if (card.playable) {
                        val url = card.toStreamItem().url
                        onPlay(playables, playables.indexOfFirst { it.url == url }.coerceAtLeast(0))
                    } else onBrowse(card.title)
                }
            }
        }
    }
}

@Composable
private fun Card(card: HomeCard, currentUrl: String?, onClick: () -> Unit) {
    val isCurrent = card.playable && currentUrl != null && card.toStreamItem().url == currentUrl
    Column(Modifier.width(146.dp).clickable { onClick() }) {
        Box(Modifier.size(146.dp)) {
            Thumb(card.thumbnailUrl, Modifier.size(146.dp), 12.dp)
            if (isCurrent) Box(Modifier.size(146.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Red, RoundedCornerShape(12.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, color = Tx0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = Tx1, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
