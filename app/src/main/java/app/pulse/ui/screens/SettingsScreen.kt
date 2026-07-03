package app.pulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                            Text("Connect session with browser", color = Tx0, fontSize = 15.sp)
                            Text("Sign in to load your personalized home", color = Tx2, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Tx3, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Section("DOWNLOADS")
            Card {
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
                        Text("PULSE", color = Tx0, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.padding(bottom = 4.dp).size(5.dp).clip(CircleShape).background(Red))
                    }
                    Text("Version 0.8.5", color = Tx2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
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
        Text(title, color = Tx0, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tx2, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Tx3, modifier = Modifier.size(18.dp))
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
