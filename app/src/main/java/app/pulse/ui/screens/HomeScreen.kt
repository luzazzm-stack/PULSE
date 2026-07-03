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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.HomeState
import app.pulse.core.StreamItem
import app.pulse.ui.components.StreamRow
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun HomeScreen(
    state: HomeState,
    currentUrl: String?,
    isPlaying: Boolean,
    onPlay: (List<StreamItem>, Int) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text("PULSE", color = Tx0, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Spacer(Modifier.width(5.dp))
            Box(Modifier.padding(bottom = 4.dp).size(6.dp).clip(CircleShape).background(Red))
        }
        Text("Trending", color = Tx2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                state.error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load trending.", color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(12.dp))
                    Text("Retry", color = OnRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 150.dp)) {
                    itemsIndexed(state.trending, key = { i, it -> it.url + i }) { i, item ->
                        StreamRow(item, item.url == currentUrl, isPlaying) { onPlay(state.trending, i) }
                    }
                }
            }
        }
    }
}
