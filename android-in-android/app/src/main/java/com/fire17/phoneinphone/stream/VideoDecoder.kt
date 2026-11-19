package com.fire17.phoneinphone.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Feeds scrcpy video packets (Annex-B H.264/H.265 access units) into a hardware MediaCodec that
 * renders straight into the shell's Surface. No YUV copies, no GL: lowest-latency path on Android.
 *
 * scrcpy sends one CONFIG packet (SPS/PPS[/VPS]) per session before the first key frame; we pass
 * it as BUFFER_FLAG_CODEC_CONFIG. A new session (rotation, resize) means a new config, so the
 * caller recreates this decoder on every Session packet.
 */
class VideoDecoder(
    private val mime: String,
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
) {
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()

    @Volatile var framesRendered = 0L
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // Realtime priority hint (0 = realtime).
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    /** Blocking-ish: waits up to 50 ms for an input slot, then drains all ready output. */
    fun queue(data: ByteArray, ptsUs: Long, config: Boolean) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(50_000)
        if (idx >= 0) {
            val buf: ByteBuffer = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (data.size > buf.capacity()) {
                Log.w(TAG, "packet ${data.size} > input buffer ${buf.capacity()}, dropping")
                c.queueInputBuffer(idx, 0, 0, 0, 0)
            } else {
                buf.put(data)
                val flags = if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                c.queueInputBuffer(idx, 0, data.size, if (ptsUs < 0) 0 else ptsUs, flags)
            }
        } else {
            Log.w(TAG, "no input buffer available, dropping packet")
        }
        drain()
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    // render=true: present as soon as possible (no A/V sync: video only).
                    c.releaseOutputBuffer(out, true)
                    framesRendered++
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> return
            }
        }
    }

    fun release() {
        try { codec?.stop() } catch (ignored: Throwable) {}
        try { codec?.release() } catch (ignored: Throwable) {}
        codec = null
    }

    companion object {
        private const val TAG = "PiP.Decoder"

        fun mimeFor(codecId: Int): String = when (codecId) {
            ScrcpyProtocol.CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            ScrcpyProtocol.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ScrcpyProtocol.CODEC_AV1 -> "video/av01" // MediaFormat.MIMETYPE_VIDEO_AV1 is API 29; minSdk is 26
            else -> throw IllegalArgumentException("unsupported codec ${ScrcpyProtocol.codecName(codecId)}")
        }
    }
}
