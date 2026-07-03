package app.pulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pulse.core.OnboardingStore
import app.pulse.playback.PlayerViewModel
import app.pulse.ui.PulseRoot
import app.pulse.ui.screens.OnboardingScreen
import app.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            PulseTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var onboarded by remember { mutableStateOf(OnboardingStore.done) }

                if (!onboarded) {
                    OnboardingScreen(onDone = { scope.launch { OnboardingStore.setDone(context) }; onboarded = true })
                } else {
                    val homeVm: HomeViewModel = viewModel()
                    val searchVm: SearchViewModel = viewModel()
                    val playerVm: PlayerViewModel = viewModel()
                    val settingsVm: SettingsViewModel = viewModel()
                    PulseRoot(homeVm, searchVm, playerVm, settingsVm)
                }
            }
        }
    }
}
