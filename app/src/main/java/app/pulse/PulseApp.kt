package app.pulse

import android.app.Application
import app.pulse.core.AuthStore
import app.pulse.core.FavoritesStore
import app.pulse.core.NewPipeDownloader
import app.pulse.core.OnboardingStore
import app.pulse.core.PlaylistStore
import app.pulse.download.DownloadManager
import coil.ImageLoader
import kotlinx.coroutines.runBlocking
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.android.gms.security.ProviderInstaller
import org.schabi.newpipe.extractor.NewPipe

class PulseApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Old phones (e.g. Galaxy J7 on Android 6-8) ship an outdated TLS/security provider whose cipher
        // suites Google's servers reject — updating it fixes HTTPS to YouTube (search, home, streaming).
        runCatching { ProviderInstaller.installIfNeeded(this) }
        NewPipe.init(NewPipeDownloader.instance)
        DownloadManager.init(this)
        PlaylistStore.init(this)
        FavoritesStore.init(this)
        runBlocking { AuthStore.load(this@PulseApp); OnboardingStore.load(this@PulseApp) }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("art_cache"))
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
