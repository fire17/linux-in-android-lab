package com.fire17.linuxdesk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.fire17.linuxdesk.LinuxDeskSettings
import com.fire17.linuxdesk.SessionBus
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sound out (spec 02 #1 v2, part a): whatever the guest plays (Chromium → PulseAudio null sink "ld") comes out
 * of the phone's speaker.
 *
 *   guest:  module-simple-protocol-tcp listen=127.0.0.1 port=<random> record=true source=ld.monitor
 *           → raw s16le 48 kHz stereo PCM, no header, as long as the socket is open
 *   app:    this class reads that socket and writes it to an AudioTrack (PERFORMANCE_MODE_LOW_LATENCY)
 *
 * Lifecycle (SessionService): [prepare] before PulseHost.start (registers the TCP module when the "Sound" setting
 * is ON, default ON), [start] after it, [stop] with the session. Reconnects every 1 s while the session runs
 * (PulseAudio restarts, slow start).
 *
 * SECURITY CAVEAT: the simple-protocol TCP stream is UNAUTHENTICATED loopback, the same caveat as spec 02 #1/#5.
 * Any app on this phone could connect to 127.0.0.1:<port> and hear what the browser plays while it is open. It is
 * bound to 127.0.0.1 only (never the network), the port is random in 20000–60000 and fresh per session, and the
 * module exists only while the session runs (PulseAudio is stopped with the session). It is record-only
 * (playback=false), so nobody can inject sound through it.
 */
object AudioOut {
    private const val OWNER = "audio-out"
    private const val RATE = 48_000
    private const val FRAME_BYTES = 4                          // s16le stereo
    private const val CHUNK = RATE / 100 * FRAME_BYTES         // 10 ms
    /** When more than this is queued in the socket, drop it: the null-sink clock and the DAC clock drift apart. */
    private const val MAX_BACKLOG = RATE / 5 * FRAME_BYTES     // 200 ms

    @Volatile private var port = 0
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var active = false

    private fun log(s: String) = SessionBus.log("sound: $s")

    /** Before PulseHost.start. Registers the loopback TCP record module when Sound is ON. */
    fun prepare(ctx: Context) {
        stop()
        if (!LinuxDeskSettings(ctx).sound) {
            PulseHost.release(OWNER)
            port = 0
            return
        }
        port = PulseHost.freeLoopbackPort()
        PulseHost.require(OWNER, listOf(
            "module-simple-protocol-tcp listen=127.0.0.1 port=$port record=true playback=false " +
                "source=${PulseHost.SINK}.monitor format=s16le rate=$RATE channels=2"
        ))
    }

    /** After PulseHost.start. No-op when Sound is OFF or PulseAudio is not running. */
    fun start() {
        if (port == 0) return
        if (!PulseHost.running) { log("PulseAudio is not running; the session stays silent"); return }
        active = true
        val p = port
        worker = Thread({ loop(p) }, "ld-audio-out").apply { isDaemon = true; start() }
        log("playing guest audio from 127.0.0.1 (loopback only, random port)")
    }

    fun stop() {
        active = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.let { it.interrupt(); try { it.join(1500) } catch (_: InterruptedException) {} }
        worker = null
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(maxOf(min, CHUNK * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun loop(p: Int) {
        var failures = 0
        while (active) {
            var track: AudioTrack? = null
            try {
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", p), 2_000)
                s.soTimeout = 5_000
                track = newTrack().also { it.play() }
                if (failures > 0) log("connected after $failures retries")
                failures = 0
                pump(s.getInputStream(), track)
            } catch (e: Exception) {
                if (active && (failures == 0 || failures % 30 == 0)) log("stream not available (${e.javaClass.simpleName}); retrying")
                failures++
            } finally {
                try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
            if (!active) break
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
        }
    }

    private fun pump(input: InputStream, track: AudioTrack) {
        val buf = ByteArray(CHUNK * 2)
        var carry = 0                       // keep writes frame-aligned
        while (active) {
            val backlog = input.available()
            if (carry == 0 && backlog > MAX_BACKLOG) {     // only on a frame boundary, so channels never swap
                val drop = (backlog - CHUNK * 4) / FRAME_BYTES * FRAME_BYTES
                input.skip(drop.toLong())
            }
            val n = input.read(buf, carry, buf.size - carry)
            if (n < 0) throw java.io.EOFException("PulseAudio closed the stream")
            val total = carry + n
            val aligned = total / FRAME_BYTES * FRAME_BYTES
            if (aligned > 0) {
                val w = track.write(buf, 0, aligned)
                if (w < 0) throw IllegalStateException("AudioTrack.write=$w")
            }
            carry = total - aligned
            if (carry > 0) System.arraycopy(buf, aligned, buf, 0, carry)
        }
    }
}
