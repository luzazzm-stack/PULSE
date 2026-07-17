package app.pulse.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Transcodes a downloaded .m4a / AAC (audio/mp4a-latm) file into a real .mp3, fully on-device
 * with no FFmpeg: MediaExtractor + MediaCodec decode to 16-bit little-endian PCM, then the LAME
 * JNI wrapper encodes PCM to MP3. minSdk 21 safe (getInputBuffer/getOutputBuffer are API 21+).
 *
 * Notes that matter (see the verified research): LAME's `samples` argument is samples-PER-CHANNEL,
 * not the short[] length; the authoritative sample-rate/channel-count come from the codec OUTPUT
 * format (some HE-AAC streams report a different rate in the container); always flush() at the end
 * or the last frame is dropped. Call off the main thread — it is CPU-heavy and blocking.
 */
object Mp3Transcoder {

    private const val TIMEOUT_US = 10_000L
    private const val MP3_BITRATE_KBPS = 192
    private const val MP3_QUALITY = 5 // 0 = best/slowest .. 9 = worst/fastest

    fun transcode(input: File, output: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var lame: AndroidLame? = null
        var out: FileOutputStream? = null
        try {
            extractor.setDataSource(input.absolutePath)

            // 1) Select the audio track and read its format.
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; inputFormat = f; break }
            }
            require(trackIndex >= 0 && inputFormat != null) { "No audio track in ${input.name}" }
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            // 2) Configure + start the decoder.
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            out = FileOutputStream(output)
            val info = MediaCodec.BufferInfo()

            // Provisional values from the container; the OUTPUT format is authoritative.
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            var mp3Buf = ByteArray(0)
            var sawInputEOS = false
            var sawOutputEOS = false
            var stallGuard = 0   // guards against a malformed file whose decoder never emits an EOS output buffer

            fun buildLame(rate: Int, ch: Int) = LameBuilder()
                .setInSampleRate(rate)
                .setOutSampleRate(rate)
                .setOutChannels(ch)
                .setOutBitrate(MP3_BITRATE_KBPS)
                .setQuality(MP3_QUALITY)
                .build()

            while (!sawOutputEOS) {
                // ---- feed input ----
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- drain output ----
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        stallGuard = 0
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        lame?.close()
                        lame = buildLame(sampleRate, channels)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // After input EOS the decoder should flush its remaining frames quickly; if it never
                        // surfaces an EOS output buffer (corrupt/truncated file), bail instead of looping forever
                        // and holding transcodeMutex. ~3000 * TIMEOUT_US (10ms) ≈ 30s of pure stall.
                        if (sawInputEOS && ++stallGuard > 3000) error("MP3 transcode stalled: decoder emitted no end-of-stream")
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated; ignore */ }
                    outIndex >= 0 -> {
                        stallGuard = 0
                        if (info.size > 0) {
                            // Fallback: some devices deliver PCM before firing FORMAT_CHANGED.
                            if (lame == null) lame = buildLame(sampleRate, channels)

                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)

                            val shortCount = info.size / 2
                            val pcm = ShortArray(shortCount)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)

                            val needed = (7200 + shortCount * 1.25).toInt()
                            if (mp3Buf.size < needed) mp3Buf = ByteArray(needed)

                            val encoded = if (channels >= 2) {
                                // Interleaved LRLR: `samples` = per-channel count.
                                lame!!.encodeBufferInterLeaved(pcm, shortCount / 2, mp3Buf)
                            } else {
                                // Mono: feed the same buffer as L & R; samples == total == per-channel.
                                lame!!.encode(pcm, pcm, shortCount, mp3Buf)
                            }
                            if (encoded > 0) out.write(mp3Buf, 0, encoded)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }
            }

            // 3) Flush LAME's internal buffer (the last partial MP3 frame).
            lame?.let {
                if (mp3Buf.size < 7200) mp3Buf = ByteArray(7200)
                val flushed = it.flush(mp3Buf)
                if (flushed > 0) out.write(mp3Buf, 0, flushed)
            }
            out.flush()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { lame?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { out?.close() } catch (_: Exception) {}
        }
    }
}
