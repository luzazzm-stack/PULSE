package app.pulse.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The single read permission needed to list device audio, resolved per API level. */
val AUDIO_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Scans the device's media library (MediaStore) for music the user already has stored locally —
 * including songs downloaded by OTHER apps into Music/, Download/, etc. Returns [StreamItem]s whose
 * `url` is a playable content:// uri; PlayerViewModel.resolveAndPlay plays those directly (no network).
 * Returns an empty list if the read permission isn't granted. Runs on IO.
 */
suspend fun scanLocalAudio(context: Context): List<StreamItem> = withContext(Dispatchers.IO) {
    if (!hasAudioPermission(context)) return@withContext emptyList()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    val albumArtBase = Uri.parse("content://media/external/audio/albumart")
    val out = ArrayList<StreamItem>()
    runCatching {
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)      // API 29 col; -1 on odd ROMs
            val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val rawArtist = cursor.getString(artistCol)
                val artist = if (rawArtist.isNullOrEmpty() || rawArtist == MediaStore.UNKNOWN_STRING) "Unknown artist" else rawArtist
                val durSec = if (durCol >= 0) cursor.getLong(durCol) / 1000L else 0L
                val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                val art = if (albumId > 0) ContentUris.withAppendedId(albumArtBase, albumId).toString() else null
                out += StreamItem(
                    url = contentUri.toString(),
                    title = cursor.getString(titleCol) ?: "Unknown title",
                    uploader = artist,
                    durationSec = durSec,
                    thumbnailUrl = art,
                )
            }
        }
    }
    out
}
