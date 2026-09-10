package app.pulse.download

import android.net.Uri
import androidx.compose.runtime.Immutable
import app.pulse.core.StreamItem
import java.io.File

enum class DlFormat(val ext: String, val label: String) {
    MP4("mp4", "Video · MP4 (max)"),
    M4A("m4a", "Audio · M4A (original)"),
    MP3("mp3", "Audio · MP3 (converted)"),
}

enum class DlStatus { Queued, Downloading, Completed, Failed }

@Immutable
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val format: DlFormat,
    val status: DlStatus,
    val progress: Float = 0f,
    val filePath: String? = null,
    /** Local side-car cover ({id}.jpg next to the media file), saved during download so the
     *  Downloads list / NowPlaying / media notification can show art fully offline. */
    val artPath: String? = null,
)

/**
 * A completed download as a queue item: its file (content:// or path) plays directly. The downloaded side-car
 * cover goes in as a file:// uri — Coil (mini-player/NowPlaying) and Media3's notification bitmap loader both
 * read it, so art shows fully offline. Null until the download has a file.
 */
fun DownloadItem.toPlayable(): StreamItem? {
    val location = filePath ?: return null
    val art = artPath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it).toString() } ?: thumbnailUrl
    return StreamItem(url = location, title = title, uploader = uploader, durationSec = 0, thumbnailUrl = art)
}
