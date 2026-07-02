package app.pulse.core

import androidx.compose.runtime.Immutable

@Immutable
data class StreamItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

@Immutable
data class StreamData(
    val url: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val audioUrl: String?,   // best progressive audio (for playback / M4A download)
    val videoUrl: String?,   // best progressive muxed MP4 (for max-quality video download)
)

fun Long.secToClock(): String {
    if (this <= 0) return "0:00"
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun Long.msToClock(): String = (this / 1000).secToClock()
