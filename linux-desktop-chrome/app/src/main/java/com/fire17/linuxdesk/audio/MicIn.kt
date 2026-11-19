package com.fire17.linuxdesk.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Phone microphone → Linux. Captures with AudioRecord and streams raw PCM (s16le, [RATE] Hz, [CHANNELS] ch)
 * to the guest's module-simple-protocol-tcp on 127.0.0.1:<port>. proot shares the app's network namespace,
 * so the guest's loopback is the app's loopback. Guest wiring is in [MicGuest].
 *
 * CONSENT (Tami: "only if I approve and turn it on"):
 *  - [consent] lives in memory only and starts false on every process start. Nothing persists it.
 *  - Only the in-app switch (LauncherActivity) sets it; it then sends SessionService.ACTION_MIC_APPLY, which
 *    carries no value (an intent cannot say "on"). The genie Router has no
 *    verb that reaches this class, and refuses mic.* / audio.in* outright.
 *  - [stop] (switch OFF, notification "Stop", session end) releases AudioRecord on the calling thread, at once.
 *
 * PRIVACY: audio buffers go only to the loopback socket. Nothing here writes them to disk or logs them; log lines
 * carry state words only (no sizes, levels or samples).
 */
object MicIn {
    const val RATE = 48_000
    const val CHANNELS = 1   // mono: the phone has one useful voice mic; MicGuest declares channels=1 to match
    private const val TAG = "LinuxDeskMic"
    private const val FRAME_MS = 20
    private const val CHUNK_BYTES = RATE / 1000 * FRAME_MS * CHANNELS * 2   // 1920 bytes per 20 ms

    /** The person's approval. In memory only; reset to false when the session ends. Set true ONLY by the
     *  in-app switch (LauncherActivity, after RECORD_AUDIO is granted); set false by switch / Stop / session end. */
    @Volatile var consent = false
        private set

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var ns: NoiseSuppressor? = null
    private val lock = Any()

    /** Optional state sink (SessionBus.log). Never receives audio, only words like "connected". */
    @Volatile var onState: ((String) -> Unit)? = null

    val active: Boolean get() = running

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** true: the in-app switch only. false: anyone (stopping is always allowed). */
    internal fun setConsent(on: Boolean) { consent = on; if (!on) stop() }

    /**
     * Starts capture to 127.0.0.1:[port]. No-op without [consent] or RECORD_AUDIO. Reconnects with backoff if the
     * guest end drops (e.g. PulseAudio restarts); while disconnected, audio is dropped, never buffered.
     */
    @SuppressLint("MissingPermission")   // checked right here
    fun start(ctx: Context, port: Int): Boolean = synchronized(lock) {
        if (!consent || !hasPermission(ctx) || running) return running
        val rec = openRecord() ?: run { state("mic: could not open the microphone"); return false }
        record = rec
        running = true
        thread = Thread({ pump(rec, port) }, "LinuxDeskMicIn").apply { isDaemon = true; start() }
        AudioOut.setVoiceMode(true)   // speaker becomes the echo canceller's reference
        true
    }

    /** Stops capture immediately: the mic is released before this returns. Safe from any thread, idempotent. */
    fun stop() {
        val t: Thread?
        synchronized(lock) {
            if (!running && record == null) return
            running = false
            t = thread; thread = null
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            releaseEffects()
            record?.let { r ->
                try { r.stop() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
            }
            record = null
        }
        t?.interrupt()
        AudioOut.setVoiceMode(false)
        state("mic: off")
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val chMask = if (CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(RATE, chMask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        // VOICE_COMMUNICATION gives echo cancellation / noise suppression where the device has them (good for
        // calls in Chromium); fall back to the raw MIC source.
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord(src, RATE, chMask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
            } catch (_: Exception) { null } ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) { attachEffects(r); return r }
            r.release()
        }
        return null
    }

    /** Speaker → mic echo (Linux sound out of the phone speaker feeding back into the mic): ask the platform for
     *  its echo canceller + noise suppressor on this capture session. AudioOut plays as voice-communication while
     *  the mic is on, so the canceller gets the speaker as its reference. */
    private fun attachEffects(r: AudioRecord) {
        val id = r.audioSessionId
        aec = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(id)?.apply { enabled = true } else null
        } catch (_: Exception) { null }
        ns = try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(id)?.apply { enabled = true } else null
        } catch (_: Exception) { null }
        state(if (aec?.enabled == true) "mic: echo cancel on" else "mic: echo cancel unavailable")
    }

    private fun releaseEffects() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null; ns = null
    }

    private fun pump(rec: AudioRecord, port: Int) {
        val buf = ByteArray(CHUNK_BYTES)
        var out: OutputStream? = null
        var backoffMs = 250L
        var nextConnectAt = 0L
        try {
            rec.startRecording()
            state("mic: on")
            while (running) {
                val n = rec.read(buf, 0, buf.size)   // blocks ~20 ms; also paces the loop
                if (!running) break
                if (n <= 0) { if (n < 0) { state("mic: capture error"); break }; continue }
                if (out == null && System.currentTimeMillis() >= nextConnectAt) {
                    out = try {
                        val s = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress("127.0.0.1", port), 1000) }
                        synchronized(lock) { if (running) socket = s else { s.close(); null } }
                        backoffMs = 250L
                        state("mic: connected to Linux")
                        s.getOutputStream()
                    } catch (_: IOException) {
                        nextConnectAt = System.currentTimeMillis() + backoffMs
                        backoffMs = (backoffMs * 2).coerceAtMost(4000L)
                        null
                    }
                }
                val o = out ?: continue   // not connected: drop this chunk
                try {
                    o.write(buf, 0, n)
                } catch (_: IOException) {
                    if (!running) break
                    state("mic: Linux side dropped; reconnecting")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    out = null
                    nextConnectAt = System.currentTimeMillis() + backoffMs
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "mic pump ended: ${t.javaClass.simpleName}")
        } finally {
            java.util.Arrays.fill(buf, 0)
            if (running) stop()   // capture error path: release the mic, never leave it half-open
        }
    }

    private fun state(s: String) {
        Log.i(TAG, s)
        try { onState?.invoke(s) } catch (_: Exception) {}
    }
}
