package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.PulseTheme
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx3

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { PulseTheme { Splash() } }
    }
}

@Composable
private fun Splash() {
    Column(
        Modifier.fillMaxSize().background(Bg0),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("PULSE", color = Tx0, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.padding(bottom = 8.dp).size(8.dp).clip(CircleShape).background(Red))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Every song. Every video. Downloaded. Ad-free.",
            color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
    }
}
