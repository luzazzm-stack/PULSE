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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.SearchState
import app.pulse.core.StreamItem
import app.pulse.ui.components.StreamRow
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun SearchScreen(
    state: SearchState,
    currentUrl: String?,
    isPlaying: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (List<StreamItem>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Bg3, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Tx2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Songs on YouTube Music", color = Tx2, fontSize = 14.sp)
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Tx0, fontSize = 14.sp),
                    cursorBrush = SolidColor(Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.query.isNotEmpty()) {
                Box(Modifier.size(36.dp).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "clear", tint = Tx2, modifier = Modifier.size(18.dp))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
                state.error != null -> Hint("Couldn't search — check your connection.\n${state.error}")
                state.searched && state.results.isEmpty() -> Hint("No results.")
                !state.searched -> Hint("Search YouTube Music — ad-free.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 150.dp)) {
                    itemsIndexed(state.results, key = { i, it -> it.url + i }) { i, item ->
                        StreamRow(item, item.url == currentUrl, isPlaying) { onPlay(state.results, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tx3, fontSize = 13.sp)
    }
}
