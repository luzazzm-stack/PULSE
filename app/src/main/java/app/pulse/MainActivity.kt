package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.playback.PlayerViewModel
import app.pulse.ui.PulseRoot
import app.pulse.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            PulseTheme {
                val searchVm: SearchViewModel = viewModel()
                val playerVm: PlayerViewModel = viewModel()
                PulseRoot(searchVm, playerVm)
            }
        }
    }
}
