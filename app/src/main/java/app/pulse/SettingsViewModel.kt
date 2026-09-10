package app.pulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.AppSettings
import app.pulse.core.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<AppSettings> =
        SettingsStore.flow(getApplication()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setVideo(v: Boolean) = viewModelScope.launch { SettingsStore.setVideo(getApplication(), v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { SettingsStore.setWifiOnly(getApplication(), v) }
    fun setPreferVideo(v: Boolean) = viewModelScope.launch { SettingsStore.setPreferVideo(getApplication(), v) }
    fun setMaxConcurrent(v: Int) = viewModelScope.launch { SettingsStore.setMaxConcurrent(getApplication(), v) }
    fun setDownloadFolder(treeUri: String?, label: String?) = viewModelScope.launch { SettingsStore.setDownloadFolder(getApplication(), treeUri, label) }
    fun setDownloadPreset(relPath: String) = viewModelScope.launch { SettingsStore.setDownloadPreset(getApplication(), relPath) }
}
