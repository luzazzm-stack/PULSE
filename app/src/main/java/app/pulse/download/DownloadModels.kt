package app.pulse.download

import androidx.compose.runtime.Immutable

enum class DlFormat(val ext: String, val label: String) {
    MP4("mp4", "Video · MP4 (max)"),
    M4A("m4a", "Audio only · M4A"),
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
)
