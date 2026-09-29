package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.AppSettings
import app.pulse.ui.theme.Bg1
import app.pulse.ui.theme.Bg3
import app.pulse.ui.theme.Bg4
import app.pulse.ui.theme.Line08
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

@Composable
fun SettingsScreen(
    s: AppSettings,
    completedDownloads: Int,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onSetVideo: (Boolean) -> Unit,
    onSetWifi: (Boolean) -> Unit,
    onSetPreferVideo: (Boolean) -> Unit,
    onSetMax: (Int) -> Unit,
    downloadLocation: String,
    locationPresets: List<String>,
    selectedPreset: String?,
    customFolder: String?,
    onPickPreset: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    // Root tap consumer: the header row's dead area (the Bg0 background lives on the wrapper in
    // PulseRoot and is not hit-testable) would otherwise let taps reach the tab content behind.
    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.CenterStart) {
                Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", color = Tx0, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            Section("ACCOUNT")
            Card {
                if (connected) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connected", color = Tx0, fontSize = 15.sp)
                            Text("Your YouTube Music account", color = Tx2, fontSize = 12.sp)
                        }
                        Text("Disconnect", color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onDisconnect() })
                    }
                } else {
                    Row(Modifier.fillMaxWidth().height(60.dp).clickable { onConnect() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect YouTube Music account", color = Tx0, fontSize = 15.sp)
                            Text("One-time sign-in — personalized home + liked music", color = Tx2, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Tx3, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Section("DOWNLOADS")
            Card {
                // Where finished downloads are saved: a preset shared-storage folder, or any folder via the system picker.
                var showLocations by remember { mutableStateOf(false) }
                ValueRow("Download location", downloadLocation) { showLocations = !showLocations }
                if (showLocations) {
                    locationPresets.forEachIndexed { i, p ->
                        Divider()
                        ChoiceRow(p, if (i == 0) "Default" else null, selected = p == selectedPreset) { onPickPreset(p) }
                    }
                    Divider()
                    ChoiceRow("Other folder…", customFolder ?: "Pick any folder on your phone", selected = customFolder != null) { onPickFolder() }
                    Text(
                        "New downloads go here. Songs you've already downloaded stay where they are.",
                        color = Tx3, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                Divider()
                ValueRow("Default format", if (s.defaultVideo) "Video (MP4)" else "Audio (M4A)") { onSetVideo(!s.defaultVideo) }
                Divider()
                ValueRow("Default quality", "Maximum available") {}
                Divider()
                ToggleRow("Audio-only downloads", !s.defaultVideo) { onSetVideo(!it) }
                Divider()
                ToggleRow("Download over Wi-Fi only", s.wifiOnly) { onSetWifi(it) }
                Divider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Max simultaneous downloads", color = Tx0, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { n ->
                            val active = n == s.maxConcurrent
                            Text(
                                "$n", color = if (active) Tx0 else Tx2, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.size(32.dp, 26.dp).clip(RoundedCornerShape(6.dp)).background(if (active) Bg4 else Bg3).clickable { onSetMax(n) }.padding(top = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Section("PLAYBACK & STREAMING")
            Card {
                ValueRow("Streaming quality", "High (256 kbps)") {}
                Divider()
                ToggleRow("Prefer video when streaming", s.preferVideoStreaming) { onSetPreferVideo(it) }
                Divider()
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Bg3), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Tx1, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Ad-free playback", color = Tx0, fontSize = 15.sp)
                        Text("Native player — no ads, ever", color = Tx2, fontSize = 12.sp)
                    }
                }
            }

            Section("STORAGE")
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloaded tracks", color = Tx0, fontSize = 15.sp)
                        Text("$completedDownloads", color = Tx0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Line08)) {
                        Box(Modifier.fillMaxWidth(if (completedDownloads > 0) 0.6f else 0.02f).height(4.dp).background(Red))
                    }
                }
            }

            Section("ABOUT")
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Emma", color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.padding(bottom = 4.dp).size(5.dp).clip(CircleShape).background(Red))
                    }
                    Text("Version 0.12.6", color = Tx2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("A native, ad-free player", color = Tx3, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun Section(label: String) {
    Text(label, color = Tx3, fontSize = 11.sp, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg1).border(1.dp, Line08, RoundedCornerShape(12.dp))) {
        content()
    }
}

@Composable private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(Line08)) }

@Composable private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).clickable { onClick() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // The title keeps its natural width and the value takes what's left, ellipsized — a long picked-folder name
        // would otherwise squeeze the title onto two lines on a 360dp screen.
        Text(title, color = Tx0, fontSize = 15.sp, maxLines = 1, softWrap = false)
        Text(
            value, color = Tx2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
        )
        Icon(Icons.Rounded.ChevronRight, null, tint = Tx3, modifier = Modifier.size(18.dp))
    }
}

/** One option of an expanded ValueRow: indented, with a check on the selected one. */
@Composable private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onClick() }.padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tx0, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = Tx3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, "selected", tint = Red, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun ToggleRow(title: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Tx0, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(48.dp, 28.dp).clip(RoundedCornerShape(99.dp)).background(if (on) Red else Bg4).clickable { onToggle(!on) }.padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) OnRed else Tx1))
        }
    }
}
