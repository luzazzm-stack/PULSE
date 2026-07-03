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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.SearchState
import app.pulse.core.StreamItem
import app.pulse.ui.components.StreamRow
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.Line12
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

private val GENRES = listOf(
    "Pop" to listOf(Color(0xFF2B2A5E), Color(0xFFC0455E)),
    "Hip-Hop" to listOf(Color(0xFF5A1F2E), Color(0xFFD1682F)),
    "Bollywood" to listOf(Color(0xFF6B243F), Color(0xFFC0455E)),
    "Nepali" to listOf(Color(0xFF123A4A), Color(0xFF3A7D78)),
    "Chill" to listOf(Color(0xFF4B2B6B), Color(0xFFB7623A)),
    "Lofi" to listOf(Color(0xFF1C3A5E), Color(0xFF5A3A8E)),
    "Workout" to listOf(Color(0xFF243B6E), Color(0xFF3A7D78)),
    "Party" to listOf(Color(0xFF3A5A2E), Color(0xFF8E7A3A)),
)

@Composable
fun SearchScreen(
    state: SearchState,
    currentUrl: String?,
    isPlaying: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onClearRecents: () -> Unit,
    onClearQuery: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Bg2).border(1.dp, Line12, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Tx2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Songs, artists, albums, videos", color = Tx3, fontSize = 15.sp)
                BasicTextField(
                    value = state.query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(color = Tx0, fontSize = 15.sp), cursorBrush = SolidColor(Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.query.isNotEmpty()) {
                Box(Modifier.size(30.dp).clickable { onClearQuery() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "clear", tint = Tx2, modifier = Modifier.size(18.dp))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                state.error != null -> Hint("Couldn't search — check your connection.")
                state.searched && state.results.isEmpty() -> Hint("No results for \"${state.query}\".")
                state.searched -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp)) {
                    itemsIndexed(state.results, key = { i, it -> it.url + i }) { i, item ->
                        StreamRow(item, item.url == currentUrl, isPlaying) { onPlay(state.results, i) }
                    }
                }
                else -> Idle(state, onSearchFor, onClearRecents)
            }
        }
    }
}

@Composable
private fun Idle(state: SearchState, onSearchFor: (String) -> Unit, onClearRecents: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 150.dp)) {
        if (state.recents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
                Text("Clear", color = Tx1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onClearRecents() })
            }
            state.recents.forEach { r ->
                Row(Modifier.fillMaxWidth().height(48.dp).clickable { onSearchFor(r) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = Tx3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(r, color = Tx0, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Text("BROWSE ALL", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GENRES.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (name, colors) -> GenreTile(name, colors, Modifier.weight(1f)) { onSearchFor(name) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GenreTile(name: String, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(104.dp).clip(RoundedCornerShape(12.dp)).background(Bg2).border(1.dp, Line08, RoundedCornerShape(12.dp)).clickable { onClick() }) {
        Text(name, color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 10.dp).size(64.dp).rotate(18f).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(colors)),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tx3, fontSize = 13.sp)
    }
}
