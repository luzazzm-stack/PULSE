package app.pulse.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Where finished downloads end up, so they're visible in the Files app and every other player and survive an
 * uninstall (app-private storage offers none of that): the folder chosen in Settings — a preset shared-storage
 * folder (Download/Emma by default, Download, Music…) or any folder picked with the system picker.
 *
 * A picked folder is a SAF tree (persisted grant) written through DocumentsContract; the stored location is its
 * document uri. A preset on Android 10+ publishes through MediaStore (Downloads, or Audio for Music/), which needs
 * no permission — a MediaStore content:// uri; below 10 it's a plain file copy that needs WRITE_EXTERNAL_STORAGE —
 * an absolute path. Every form plays directly (PlayerViewModel handles them all).
 */
object PublicStore {

    /** Where downloads go unless Settings says otherwise. */
    const val DEFAULT_REL_PATH = "Download/Emma"
    /** The shared-storage folders Settings offers directly (the first is the default). Android 11+'s folder picker
     *  refuses the Download folder itself, so it — and Music — are offered here, written through MediaStore. */
    val PRESETS = listOf(DEFAULT_REL_PATH, "Download", "Music/Emma", "Music")
    private const val EXTERNAL_STORAGE_DOCS = "com.android.externalstorage.documents"
    // Under the filesystem's 255-byte name limit, with room for a " (12)" de-dup suffix and the extension.
    private const val MAX_NAME_BYTES = 200

    /**
     * Copies [src] into the picked folder ([treeUri]) or the shared-storage folder [relPath] as [displayName].
     * Returns the new location, or null when it can't be published anywhere (no permission below Q, storage
     * error) — the caller then keeps [src]. Never throws.
     */
    fun publish(context: Context, src: File, displayName: String, treeUri: String? = null, relPath: String = DEFAULT_REL_PATH): String? {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.'))
            ?: "application/octet-stream"
        // The picked folder first. If it's unusable right now (SD card out, access revoked) the song still goes
        // somewhere public — the preset folder — rather than staying locked in app storage.
        if (treeUri != null) {
            runCatching { publishToTree(context, Uri.parse(treeUri), src, displayName, mime) }
                .onFailure { e -> Log.w("PULSE", "picked folder unavailable — using $relPath", e) }
                .getOrNull()?.let { return it }
        }
        // Music/ only takes audio: anything else there (an MP4 video download) goes to the default folder instead.
        val path = if (relPath.startsWith(Environment.DIRECTORY_MUSIC) && !mime.startsWith("audio/")) DEFAULT_REL_PATH else relPath
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishQ(context, src, displayName, mime, path)
            else publishLegacy(context, src, displayName, mime, path)
        }.onFailure { e -> Log.e("PULSE", "publish failed for $displayName", e) }.getOrNull()
    }

    /** Deletes a published (MediaStore uri, picked-folder document, public path) or still-private download. Best-effort. */
    fun delete(context: Context, location: String) {
        try {
            if (location.startsWith("content://")) {
                val uri = Uri.parse(location)
                if (uri.authority == MediaStore.AUTHORITY) context.contentResolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(context.contentResolver, uri)   // a picked-folder document
            } else {
                File(location).delete()
                // A pre-Q public file: rescanning the now-missing path drops its MediaStore row, or the
                // song would linger as a dead entry in other players' libraries. (App storage isn't indexed.)
                if (!location.contains("/Android/data/")) {
                    MediaScannerConnection.scanFile(context, arrayOf(location), null, null)
                }
            }
        } catch (e: Exception) {
            Log.w("PULSE", "delete failed for $location", e)
        }
    }

    /**
     * Whether a recorded download is still there. Unknown — a provider error, or storage we can't see right now
     * (unmounted, permission revoked) — counts as present: callers drop records on false, and a hiccup must
     * never cost the user a download.
     */
    fun exists(context: Context, location: String): Boolean {
        if (!location.startsWith("content://")) {
            val f = File(location)
            return f.exists() || f.parentFile?.exists() != true
        }
        val uri = Uri.parse(location)
        fun rows(u: Uri): Int? = runCatching { context.contentResolver.query(u, null, null, null, null)?.use { it.count } }.getOrNull()
        // MediaStore: a deleted row is an empty cursor; null / an error is the unknown case.
        if (uri.authority == MediaStore.AUTHORITY) return rows(uri)?.let { it > 0 } ?: true
        // A picked-folder document: a missing file comes back as a null cursor or an exception — exactly like an
        // unreachable folder. Tell them apart by asking for the folder itself: if its root answers, the file is gone.
        if ((rows(uri) ?: 0) > 0) return true
        val root = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
            ?: return true
        return (rows(root) ?: 0) == 0
    }

    /**
     * Filesystem path of a download when it can be known — plain paths as they are, and picked-folder documents on
     * device storage ("primary:Music/x.mp3") mapped back — so the device-library scan can skip Emma's own files.
     * Null for MediaStore uris (those are matched by row id instead) and for other providers.
     */
    @Suppress("DEPRECATION")   // getExternalStorageDirectory: still the primary volume's path; only read here
    fun pathOf(location: String): String? {
        if (location.startsWith("/")) return location
        val uri = Uri.parse(location)
        if (uri.authority != EXTERNAL_STORAGE_DOCS) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':')
        val root = if (volume == "primary") Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return "$root/${docId.substringAfter(':', "")}"
    }

    /**
     * "Song Title.mp3" — minus the characters shared storage rejects, cut to fit the filesystem's 255-byte name
     * limit. Counted in UTF-8 bytes on a code-point boundary: Devanagari is 3 bytes a character, emoji 4.
     */
    fun fileName(title: String, ext: String): String {
        val clean = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trim('.')
        return utf8Prefix(clean, MAX_NAME_BYTES).trimEnd().ifEmpty { "Emma download" } + "." + ext
    }

    /** Longest prefix of [s] that fits in [maxBytes] of UTF-8, never splitting a character. */
    private fun utf8Prefix(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + len > maxBytes) break
            bytes += len
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    /** A new document in the picked folder's tree; the provider de-duplicates the name itself ("Song (1).mp3"). */
    private fun publishToTree(context: Context, tree: Uri, src: File, displayName: String, mime: String): String? {
        val resolver = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, dir, mime, displayName) ?: return null
        try {
            resolver.openOutputStream(doc)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }   // no half-written husk in their folder
            throw e
        }
        return doc.toString()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishQ(context: Context, src: File, displayName: String, mime: String, relPath: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            // Hidden from other apps until the bytes are all there; MediaStore de-duplicates the name itself.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Each MediaStore collection only accepts its own top-level folders: Music/ belongs to the Audio collection.
        val collection = if (relPath.startsWith(Environment.DIRECTORY_MUSIC)) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }   // no half-written husk in the user's Downloads
            throw e
        }
    }

    @Suppress("DEPRECATION")   // getExternalStorageDirectory: the pre-Q way to reach shared storage; only called below Q
    private fun publishLegacy(context: Context, src: File, displayName: String, mime: String, relPath: String): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val dir = File(Environment.getExternalStorageDirectory(), relPath).apply { mkdirs() }
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.')
        // createNewFile claims a name atomically, so parallel downloads with the same title each get their own
        // file — an exists()-then-copy check could hand two of them the same name.
        var dest = File(dir, displayName)
        var n = 1
        while (!dest.createNewFile()) dest = File(dir, "$base (${n++}).$ext")
        try {
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } catch (e: Exception) {
            dest.delete()   // ours — claimed above — so it's safe to remove
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return dest.absolutePath
    }
}
