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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import app.pulse.ui.theme.Bg2
import app.pulse.ui.theme.OnRed
import app.pulse.ui.theme.Red
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx1
import app.pulse.ui.theme.Tx2
import app.pulse.ui.theme.Tx3

/** OAuth device-code screen: shows a short code the user approves in their normal browser. */
@Composable
fun DeviceCodeScreen(
    userCode: String,
    loadingCode: Boolean,
    connecting: Boolean,
    error: String?,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { onCancel() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("Connect your account", color = Tx0, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "Sign in with your normal browser — no password typing in the app. This loads your personalized YouTube Music home.",
                color = Tx1, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(32.dp))

            when {
                loadingCode -> {
                    CircularProgressIndicator(color = Red)
                    Spacer(Modifier.height(12.dp))
                    Text("Getting your code…", color = Tx2, fontSize = 13.sp)
                }
                error != null -> {
                    Text(error, color = Tx3, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                else -> {
                    Text("YOUR CODE", color = Tx2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Bg2).padding(horizontal = 28.dp, vertical = 16.dp)) {
                        Text(userCode, color = Tx0, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "1. Tap Open browser below\n2. Sign in if asked (you're likely already signed in)\n3. Enter the code and approve",
                        color = Tx1, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(99.dp)).background(Red).clickable { onOpenBrowser() }.padding(horizontal = 28.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null, tint = OnRed, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open browser", color = OnRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(20.dp))
                    if (connecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Red, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for approval…", color = Tx2, fontSize = 13.sp)
                        }
                    }
                    Box(Modifier.padding(top = 4.dp).height(1.dp)) {}
                    Text("This code stays valid for a few minutes.", color = Tx3, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp))
                }
            }
        }
    }
}
