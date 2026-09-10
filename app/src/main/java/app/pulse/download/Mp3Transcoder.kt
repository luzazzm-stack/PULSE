package app.pulse.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Transcodes a downloaded audio file (AAC/M4A or WebM/Opus — whatever MediaExtractor reads) into a
 * real .mp3, fully on-device with no FFmpeg: MediaExtractor + MediaCodec decode to 16-bit little-endian
 * PCM, then the LAME JNI wrapper encodes PCM to MP3. minSdk 21 safe (getInputBuffer/getOutputBuffer are API 21+).
 *
 * Decoding and encoding run on two threads: the calling thread drives MediaCodec, a dedicated thread owns
 * LAME and the output file. Measured on the user's Unisoc-class phone (a 3:46 Opus song), MediaCodec
 * round-trips cost ~35 s and LAME ~11 s — back to back that was ~46 s; overlapped, a song costs about the
 * slower half.
 *
 * Notes that matter (see the verified research): LAME's `samples` argument is samples-PER-CHANNEL,
 * not the short[] length; the authoritative sample-rate/channel-count come from the codec OUTPUT
 * format (some HE-AAC streams report a different rate in the container); always flush() at the end
 * or the last frame is dropped. Call off the main thread — it is CPU-heavy and blocking.
 */
object Mp3Transcoder {

    private const val TIMEOUT_US = 10_000L
    private const val MP3_BITRATE_KBPS = 192
    // 0 = best/slowest .. 9 = worst/fastest. 7 rather than LAME's default 5: at 192 kbps the difference is hard to hear.
    private const val MP3_QUALITY = 7
    private const val QUEUE_DEPTH = 32   // decoded PCM buffers in flight between the two threads

    /** One decoded PCM buffer handed from the decoder to the encoder, tagged with the format it was decoded in. */
    private class Pcm(val data: ShortArray, val count: Int, val rate: Int, val channels: Int)

    /**
     * [onProgress] gets 0f..1f in whole-percent steps as the source is consumed (when its duration is known).
     * An exception thrown from it aborts the transcode; both threads are stopped before this returns.
     */
    fun transcode(input: File, output: File, onProgress: (Float) -> Unit = {}) {
        val startNs = System.nanoTime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val work = ArrayBlockingQueue<Pcm>(QUEUE_DEPTH)
        val free = ArrayBlockingQueue<ShortArray>(QUEUE_DEPTH + 8)   // spent buffers come back, so steady state allocates nothing
        val end = Pcm(ShortArray(0), 0, 0, 0)
        val encoderError = AtomicReference<Throwable?>(null)
        var lameNs = 0L   // written by the encoder thread; read only after join()

        val encoder = thread(start = false, name = "mp3-encoder") {
            var lame: AndroidLame? = null
            var lameRate = 0
            var lameChannels = 0
            var mp3Buf = ByteArray(0)
            try {
                FileOutputStream(output).use { out ->
                    while (true) {
                        val chunk = work.take()
                        if (chunk === end) break
                        // The first buffer, or the decoder's output format changed mid-stream: (re)build LAME for it.
                        if (lame == null || chunk.rate != lameRate || chunk.channels != lameChannels) {
                            lame?.close()
                            lame = buildLame(chunk.rate, chunk.channels)
                            lameRate = chunk.rate
                            lameChannels = chunk.channels
                        }
                        val needed = (7200 + chunk.count * 1.25).toInt()
                        if (mp3Buf.size < needed) mp3Buf = ByteArray(needed)
                        val t = System.nanoTime()
                        val encoded = if (chunk.channels >= 2) {
                            // Interleaved LRLR: `samples` = per-channel count.
                            lame!!.encodeBufferInterLeaved(chunk.data, chunk.count / 2, mp3Buf)
                        } else {
                            // Mono: feed the same buffer as L & R; samples == total == per-channel.
                            lame!!.encode(chunk.data, chunk.data, chunk.count, mp3Buf)
                        }
                        lameNs += System.nanoTime() - t
                        free.offer(chunk.data)
                        if (encoded > 0) out.write(mp3Buf, 0, encoded)
                    }
                    // Flush LAME's internal buffer (the last partial MP3 frame).
                    lame?.let {
                        if (mp3Buf.size < 7200) mp3Buf = ByteArray(7200)
                        val flushed = it.flush(mp3Buf)
                        if (flushed > 0) out.write(mp3Buf, 0, flushed)
                    }
                    out.flush()
                }
            } catch (_: InterruptedException) {
                // The decoder side aborted (failure or cancel) — nothing left to finish.
            } catch (e: Throwable) {
                encoderError.set(e)
            } finally {
                try { lame?.close() } catch (_: Exception) {}
            }
        }

        /** Hands [chunk] to the encoder, never blocking forever on a dead one (a full queue nobody drains). */
        fun handOver(chunk: Pcm) {
            while (!work.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
                encoderError.get()?.let { throw it }
                check(encoder.isAlive) { "mp3 encoder stopped" }
            }
        }

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

            // 2) Configure + start the decoder, then the encoder thread.
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            encoder.start()

            val info = MediaCodec.BufferInfo()

            // Provisional values from the container; the OUTPUT format is authoritative.
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var lastPct = -1

            var sawInputEOS = false
            var sawOutputEOS = false
            var stallGuard = 0   // guards against a malformed file whose decoder never emits an EOS output buffer

            while (!sawOutputEOS) {
                encoderError.get()?.let { throw it }   // e.g. disk full — no point decoding the rest

                // ---- feed input: fill EVERY free decoder slot without waiting, so the decoder always has work ----
                while (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) {
                            val pct = (extractor.sampleTime * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                        }
                        extractor.advance()
                    }
                }

                // ---- drain output: copy the PCM out and hand it to the encoder thread ----
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        stallGuard = 0
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortCount = info.size / 2
                            val pcm = free.poll()?.takeIf { it.size >= shortCount } ?: ShortArray(maxOf(shortCount, 8192))
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, shortCount)
                            handOver(Pcm(pcm, shortCount, sampleRate, channels))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }
            }

            // 3) Let the encoder finish the queue, flush the last frame and close the file.
            handOver(end)
            encoder.join()
            encoderError.get()?.let { throw it }
            Log.i("PULSE/DL", "mp3 transcode ($mime, ${input.length() / 1024} KiB): ${(System.nanoTime() - startNs) / 1_000_000} ms total, ${lameNs / 1_000_000} ms of LAME alongside")
        } finally {
            // Failure/cancel: stop the encoder before returning so it never outlives this call — the next transcode
            // must find LAME's global native encoder closed, not still in use.
            if (encoder.isAlive) {
                encoder.interrupt()
                encoder.join()
            }
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun buildLame(rate: Int, ch: Int) = LameBuilder()
        .setInSampleRate(rate)
        .setOutSampleRate(rate)
        .setOutChannels(ch)
        .setOutBitrate(MP3_BITRATE_KBPS)
        .setQuality(MP3_QUALITY)
        .build()
}
