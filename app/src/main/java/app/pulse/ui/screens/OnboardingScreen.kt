package app.pulse.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx3

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg0).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("PULSE", color = Tx0, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Spacer(Modifier.size(7.dp))
            Box(Modifier.padding(bottom = 8.dp).size(8.dp).clip(CircleShape).background(Red))
        }
        Spacer(Modifier.height(10.dp))
        Text("AD-FREE · DOWNLOADABLE · YOURS", color = Tx3, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(36.dp))
        Text("Everything is downloadable.", color = Tx0, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 32.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "PULSE plays YouTube Music with no ads, and saves any track — MP4 video at max quality by default, audio-only one tap away — for offline listening.",
            color = Tx1, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            "Get started", color = OnRed, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onDone() }.padding(horizontal = 44.dp, vertical = 14.dp),
        )
    }
}
