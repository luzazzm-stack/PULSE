package app.pulse.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.Playlist
import app.pulse.core.StreamItem
import app.pulse.ui.components.AnimSpecs
import app.pulse.ui.components.Thumb
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xCC000000))) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable { onDismiss() })
        // Panel-level tap consumer: taps on panel dead areas (titles, padding) must neither reach
        // the scrim's dismiss nor pass through the overlay; row clickables are children and win.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Bg1).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(bottom = 8.dp)) {
            content()
        }
    }
}

@Composable
fun OverflowSheet(item: StreamItem, onDismiss: () -> Unit, onAddToPlaylist: () -> Unit, onAddToQueue: () -> Unit, onDownload: () -> Unit, onShare: () -> Unit) {
    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item.thumbnailUrl, Modifier.size(44.dp), 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.uploader, color = Tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line08))
        ActionRow(Icons.Rounded.PlaylistAdd, "Add to playlist", onAddToPlaylist)
        ActionRow(Icons.Rounded.QueueMusic, "Add to queue", onAddToQueue)
        ActionRow(Icons.Rounded.Download, "Download", onDownload)
        ActionRow(Icons.Rounded.Share, "Share", onShare)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Pressed-state dip: the animated scale is read only inside the graphicsLayer block, so the
    // press animation runs entirely in the layer/draw phase — zero extra recompositions.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, AnimSpecs.fast(), label = "pressScale")
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Tx1, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Tx0, fontSize = 15.sp)
    }
}

@Composable
fun AddToPlaylistSheet(playlists: List<Playlist>, onDismiss: () -> Unit, onNew: () -> Unit, onAdd: (String) -> Unit) {
    Sheet(onDismiss) {
        Text("Add to playlist", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().height(52.dp).clickable { onNew() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Bg2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = Red, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("New playlist", color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(playlists, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().height(56.dp).clickable { onAdd(p.id) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Bg2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.QueueMusic, null, tint = Tx2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Tx0, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.tracks.size} songs", color = Tx2, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        // Card-level tap consumer: without it, taps on the card's own padding bubble to the scrim's
        // clickable above and dismiss the dialog mid-typing. The scrim (outside the card, incl. the
        // 28.dp margin) still dismisses; Cancel/Create/text field are children and win.
        Column(Modifier.padding(28.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Bg2).pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)) {
            Text("New playlist", color = Tx0, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line08))
            BasicTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = TextStyle(color = Tx0, fontSize = 15.sp), cursorBrush = SolidColor(Red),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text("Playlist name", color = Tx3, fontSize = 15.sp); inner() } },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line08))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", color = Tx1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create", color = OnRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onCreate(name) }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
    }
}
