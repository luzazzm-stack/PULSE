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
 * Rows whose file path is in [excludePaths] are skipped — Emma's own downloads saved as plain files or into a
 * folder picked in Settings, which the Downloads list already shows (MediaStore-published ones PulseRoot hides by id).
 * Returns an empty list if the read permission isn't granted. Runs on IO.
 */
@Suppress("DEPRECATION")   // MediaStore DATA: deprecated on 10+ but still readable — the one way to match a row to a path
suspend fun scanLocalAudio(context: Context, excludePaths: Set<String> = emptySet()): List<StreamItem> = withContext(Dispatchers.IO) {
    if (!hasAudioPermission(context)) return@withContext emptyList()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val byPath = excludePaths.isNotEmpty()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
    ) + (if (byPath) arrayOf(MediaStore.Audio.Media.DATA) else emptyArray<String>())
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
            val dataCol = if (byPath) cursor.getColumnIndex(MediaStore.Audio.Media.DATA) else -1
            while (cursor.moveToNext()) {
                if (dataCol >= 0 && cursor.getString(dataCol) in excludePaths) continue
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
