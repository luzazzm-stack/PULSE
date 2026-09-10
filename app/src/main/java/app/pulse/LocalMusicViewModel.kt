package app.pulse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.StreamItem
import app.pulse.core.scanLocalAudio
import app.pulse.download.DownloadManager
import app.pulse.download.PublicStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds the device-library scan results for the Downloads page's "On this device" section. */
class LocalMusicViewModel(app: Application) : AndroidViewModel(app) {

    private val _tracks = MutableStateFlow<List<StreamItem>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    /** True once a first scan has completed — before that, [tracks] is empty because nothing has looked yet. */
    private val _scanned = MutableStateFlow(false)
    val scanned = _scanned.asStateFlow()

    private var scannedOnce = false

    /** Scan the device once (or [force] a manual rescan). Safe to call repeatedly; it de-dupes. */
    fun scan(force: Boolean = false) {
        android.util.Log.i("PULSE", "device scan requested: force=$force scanning=${_scanning.value} scannedOnce=$scannedOnce")
        if (_scanning.value) return
        if (scannedOnce && !force) return
        _scanning.value = true
        viewModelScope.launch {
            // Emma's own downloads that live at a known path (plain files, or a folder picked in Settings) — the
            // Downloads list already shows them.
            val ownPaths = DownloadManager.items.value.mapNotNullTo(HashSet<String>()) { d -> d.filePath?.let(PublicStore::pathOf) }
            val t0 = System.nanoTime()
            _tracks.value = scanLocalAudio(getApplication(), ownPaths)
            android.util.Log.i("PULSE", "device scan: ${_tracks.value.size} tracks in ${(System.nanoTime() - t0) / 1_000_000} ms")
            scannedOnce = true
            _scanning.value = false
            _scanned.value = true
        }
    }
}
