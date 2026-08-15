package app.pulse.download

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal hand-rolled ID3v2.3 writer — no tagging library in the app, and LAME emits a bare MP3
 * stream with NO tags at all, so freshly transcoded downloads showed up as "Unknown" with no art
 * in every other player. This prepends [ID3 tag][audio bytes] so title/artist/cover travel with
 * the file.
 *
 * Format details that actually matter (v2.3 chosen because it's the most widely supported):
 *  - The 10-byte tag header ends in a 28-bit SYNCSAFE size (7 bits per byte, high bit always 0).
 *  - FRAME sizes are plain 32-bit big-endian — NOT syncsafe. Syncsafe frame sizes are a v2.4-ism;
 *    mixing the two is the classic way to write tags that only half the parsers can read.
 *  - Text frames use encoding 0x01 (UTF-16 with BOM) so non-Latin titles survive; Kotlin's
 *    Charsets.UTF_16 emits the FE FF BOM itself.
 */
object Id3 {

    /**
     * Rewrites [mp3] in place as [ID3v2.3 tag][original audio] via a temp file + rename, so a
     * crash mid-write can never destroy the audio. APIC is skipped when [art] is missing or not
     * actually a JPEG (the fallback thumb URL can serve webp — a lying MIME string would break
     * that one frame in strict players). Throws on I/O failure; callers treat this as best-effort.
     */
    fun embed(mp3: File, title: String, artist: String, art: File?) {
        val frames = ByteArrayOutputStream()
        frames.write(textFrame("TIT2", title))
        frames.write(textFrame("TPE1", artist))
        art?.takeIf { it.exists() && it.length() > 2 }
            ?.readBytes()
            ?.takeIf { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }   // JPEG SOI magic
            ?.let { frames.write(apicFrame(it)) }
        val body = frames.toByteArray()
        // Syncsafe encoding caps the tag at 2^28 bytes; a cover jpg is a few hundred KB, but guard
        // anyway rather than silently write a corrupt size.
        require(body.size < (1 shl 28)) { "ID3 tag too large: ${body.size}" }

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte(); header[1] = 'D'.code.toByte(); header[2] = '3'.code.toByte()
        header[3] = 3; header[4] = 0                              // version 2.3.0
        header[5] = 0                                             // flags: none
        header[6] = ((body.size ushr 21) and 0x7F).toByte()       // 28-bit syncsafe size,
        header[7] = ((body.size ushr 14) and 0x7F).toByte()       // excluding this header
        header[8] = ((body.size ushr 7) and 0x7F).toByte()
        header[9] = (body.size and 0x7F).toByte()

        val tmp = File(mp3.parentFile, mp3.name + ".tag.tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(header)
                out.write(body)
                mp3.inputStream().use { it.copyTo(out) }
            }
            // Same-directory rename; on Android this maps to rename(2) and replaces atomically,
            // but some filesystems refuse an existing destination — delete + retry covers those.
            if (!tmp.renameTo(mp3)) {
                mp3.delete()
                check(tmp.renameTo(mp3)) { "rename failed for ${mp3.name}" }
            }
        } finally {
            // Clean up the temp ONLY while the destination still exists (no-op after a successful
            // rename; removes a half-written temp after a copy failure). If the delete+retry fallback
            // above failed AFTER deleting mp3, the temp is the last surviving copy of the audio —
            // deleting it here would destroy both copies, the exact loss this temp+rename dance exists
            // to prevent.
            if (mp3.exists()) tmp.delete()
        }
    }

    /** Text frame: [encoding 0x01][UTF-16 text with BOM]. No terminator — it's optional for text frames. */
    private fun textFrame(id: String, value: String): ByteArray {
        val payload = byteArrayOf(0x01) + value.toByteArray(Charsets.UTF_16)
        return frameHeader(id, payload.size) + payload
    }

    /** APIC: [enc 0x00][MIME "image/jpeg" 0x00][picture type 0x03 front cover][empty description 0x00][jpeg]. */
    private fun apicFrame(jpeg: ByteArray): ByteArray {
        val head = ByteArrayOutputStream()
        head.write(0x00)
        head.write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); head.write(0x00)
        head.write(0x03)
        head.write(0x00)
        val payload = head.toByteArray() + jpeg
        return frameHeader("APIC", payload.size) + payload
    }

    private fun frameHeader(id: String, size: Int): ByteArray {
        val b = ByteArray(10)
        System.arraycopy(id.toByteArray(Charsets.ISO_8859_1), 0, b, 0, 4)
        b[4] = ((size ushr 24) and 0xFF).toByte()   // v2.3 frame size: PLAIN 32-bit big-endian,
        b[5] = ((size ushr 16) and 0xFF).toByte()   // deliberately not syncsafe (see class doc)
        b[6] = ((size ushr 8) and 0xFF).toByte()
        b[7] = (size and 0xFF).toByte()
        b[8] = 0; b[9] = 0                          // frame flags: none
        return b
    }
}
