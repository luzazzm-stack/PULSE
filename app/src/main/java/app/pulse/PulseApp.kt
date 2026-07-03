package app.pulse

import android.app.Application
import app.pulse.core.AuthStore
import app.pulse.core.NewPipeDownloader
import app.pulse.download.DownloadManager
import coil.ImageLoader
import kotlinx.coroutines.runBlocking
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe

class PulseApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader.instance)
        DownloadManager.init(this)
        runBlocking { AuthStore.load(this@PulseApp) }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("art_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
