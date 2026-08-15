package app.pulse.download

import androidx.compose.runtime.Immutable

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
