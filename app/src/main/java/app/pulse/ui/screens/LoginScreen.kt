package app.pulse.ui.screens

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.pulse.ui.theme.Bg0
import app.pulse.ui.theme.Tx0
import app.pulse.ui.theme.Tx2

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

@Composable
fun LoginScreen(onConnected: (String) -> Unit, onCancel: () -> Unit) {
    val done = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxSize().background(Bg0).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { onCancel() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBackIosNew, "back", tint = Tx0, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Connect your account", color = Tx0, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Sign in to load your YouTube Music home", color = Tx2, fontSize = 11.sp)
            }
        }
        AndroidView(
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = CHROME_UA
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                            val loggedIn = cookies != null &&
                                (cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID"))
                            if (!done[0] && loggedIn && url?.contains("music.youtube.com") == true) {
                                done[0] = true
                                CookieManager.getInstance().flush()
                                onConnected(cookies!!)
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F")
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
