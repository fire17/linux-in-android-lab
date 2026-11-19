package com.fire17.linuxdesk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
    /** When more than this STAYS queued in the socket, drop it: the null-sink clock and the DAC clock drift apart. */
    private const val MAX_BACKLOG = RATE / 5 * FRAME_BYTES     // 200 ms
    /** "Smoother sound" (LinuxDeskSettings.soundSmooth, OFF by default): AudioTrack buffer of ~200 ms, not ~40 ms. */
    private const val SMOOTH_BYTES = RATE / 5 * FRAME_BYTES    // 200 ms
    private const val STATS_EVERY_MS = 10_000L

    @Volatile private var port = 0
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var active = false
    /** The live Sound switch (ld-perf). False = no speaker stream and the "ld" sink muted, without a restart. */
    @Volatile private var enabled = true
    @Volatile private var smooth = false
    /** Toggles run pactl inside proot (blocking), so they go through one background thread, in order. */
    private val ctl = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ld-sound-ctl").apply { isDaemon = true }
    }

    // ld-perf counters for this session (Performance section, "sound: stats" log lines).
    @Volatile private var track: AudioTrack? = null
    @Volatile private var underrunsDone = 0L
    @Volatile private var drops = 0L
    @Volatile private var droppedBytes = 0L
    @Volatile private var maxBurstBytes = 0
    @Volatile private var urgentPriority = false

    /** AudioTrack underruns this session (the speaker ran dry = a click), or 0 when nothing played. */
    fun underruns(): Long = underrunsDone + (try { track?.underrunCount?.toLong() } catch (_: Exception) { null } ?: 0L)

    /** One line for the Performance section. */
    fun statsLine(): String {
        val state = when { active -> "playing"; !enabled -> "OFF"; port == 0 -> "not in this session"; else -> "idle" }
        return "Sound: $state, underruns ${underruns()}, drops $drops (${droppedBytes / (RATE / 1000 * FRAME_BYTES)} ms), " +
            "max burst ${maxBurstBytes / (RATE / 1000 * FRAME_BYTES)} ms, buffer ${if (smooth) "smooth ~200 ms" else "low-latency ~40 ms"}, " +
            "pump ${if (urgentPriority) "urgent-audio" else "normal"} priority"
    }
    /** Mic on → play as voice-communication, so the platform echo canceller (MicIn) gets the speaker as its
     *  reference and the mic is not echoed back to Linux. Mic off → plain media playback. */
    @Volatile private var voiceMode = false
    /** Kept from [prepare], only to look up the built-in speaker for [voiceMode] playback. */
    @Volatile private var appCtx: Context? = null

    private fun log(s: String) = SessionBus.log("sound: $s")

    /** Before PulseHost.start. Registers the loopback TCP record module when Sound is ON. */
    fun prepare(ctx: Context) {
        stop()
        appCtx = ctx.applicationContext
        val s = LinuxDeskSettings(ctx)
        enabled = s.sound
        smooth = s.soundSmooth
        underrunsDone = 0; drops = 0; droppedBytes = 0; maxBurstBytes = 0
        if (!enabled) {
            PulseHost.release(OWNER)
            port = 0
            return
        }
        port = PulseHost.freeLoopbackPort()
        PulseHost.require(OWNER, listOf(moduleLine(port)))
    }

    private fun moduleLine(p: Int) = "module-simple-protocol-tcp listen=127.0.0.1 port=$p record=true playback=false " +
        "source=${PulseHost.SINK}.monitor format=s16le rate=$RATE channels=2"

    /** After PulseHost.start. No-op when Sound is OFF or PulseAudio is not running. */
    fun start() {
        if (!enabled) {
            // PulseAudio may run for the mic only: keep Chromium's sink muted while Sound is OFF.
            if (PulseHost.running) ctl.execute { PulseHost.pactl("set-sink-mute", PulseHost.SINK, "1") }
            return
        }
        if (port == 0 || active) return
        if (!PulseHost.running) { log("PulseAudio is not running; the session stays silent"); return }
        active = true
        val p = port
        worker = Thread({ loop(p) }, "ld-audio-out").apply { isDaemon = true; start() }
        log("playing guest audio from 127.0.0.1 (loopback only, random port)")
    }

    /**
     * The live Sound switch (launcher + desktop menu; Tami, voice 21:36). The caller saves the setting; this applies it
     * to the running session, off the UI thread. OFF: the speaker stream stops at once (AudioTrack released, pump
     * thread gone) and the "ld" sink is muted. ON: unmute and reconnect; when the session started with Sound OFF but
     * PulseAudio runs (mic), the TCP record module is loaded live. With no PulseAudio at all, ON waits for next Start.
     */
    fun setLive(ctx: Context, on: Boolean) {
        appCtx = ctx.applicationContext
        enabled = on
        ctl.execute {
            try { applyLive(on) } catch (t: Throwable) { log("switch failed (${t.javaClass.simpleName})") }
        }
    }

    private fun applyLive(on: Boolean) {
        if (enabled != on) return   // a newer flip is queued behind this one
        if (!on) {
            val was = active
            stop()
            if (PulseHost.running) PulseHost.pactl("set-sink-mute", PulseHost.SINK, "1")
            if (was) log("OFF (live): speaker stream stopped, Linux sound muted; underruns this session ${underruns()}")
            return
        }
        if (!PulseHost.running) { log("ON: applies at the next Start (no PulseAudio in this session)"); return }
        PulseHost.pactl("set-sink-mute", PulseHost.SINK, "0")
        if (active) return
        if (port == 0) {
            val p = PulseHost.freeLoopbackPort()
            val rc = PulseHost.pactl("load-module", *moduleLine(p).split(' ').toTypedArray())
            if (rc != 0) { log("ON: could not add the sound stream (pactl exit=$rc)"); return }
            port = p
            PulseHost.require(OWNER, listOf(moduleLine(p)))   // a supervised PulseAudio restart loads it again
        }
        start()
    }

    /** "Smoother sound" switch (OFF by default). Live: the pump rebuilds the AudioTrack on the same stream. */
    fun setSmooth(on: Boolean) {
        if (smooth == on) return
        smooth = on
        if (active) log(if (on) "smooth buffer ~200 ms" else "low-latency buffer ~40 ms")
    }

    fun stop() {
        active = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.let { it.interrupt(); try { it.join(1500) } catch (_: InterruptedException) {} }
        worker = null
    }

    /** MicIn calls this when the mic toggles. The running track is rebuilt with the new usage on the same stream. */
    fun setVoiceMode(on: Boolean) {
        if (voiceMode == on) return
        voiceMode = on
        if (active) log(if (on) "voice mode (mic on: echo cancel reference)" else "media mode (mic off)")
    }

    private fun newTrack(voice: Boolean, smooth: Boolean): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(if (voice) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(maxOf(min, if (smooth) SMOOTH_BYTES else CHUNK * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(if (smooth) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { if (voice) speakerFor(it) }
    }

    /**
     * Voice-communication playback goes to the earpiece on many phones, which would make Linux sound nearly silent
     * while the mic is on. With no headset, Bluetooth or USB audio connected, pin the track to the built-in speaker.
     */
    private fun speakerFor(track: AudioTrack) {
        val am = appCtx?.getSystemService(AudioManager::class.java) ?: return
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE)
        if (outs.any { it.type in headset }) return
        outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { track.setPreferredDevice(it) }
    }

    /** Releases [t] and keeps its underrun count in the session total. */
    private fun retire(t: AudioTrack?) {
        if (t == null) return
        try { underrunsDone += t.underrunCount } catch (_: Exception) {}
        if (track === t) track = null
        try { t.pause(); t.flush(); t.release() } catch (_: Exception) {}
    }

    private fun loop(p: Int) {
        // The pump feeds a ~40 ms device buffer from a TCP stream; at the default (nice 0, measured on Tami's phone
        // 21:41: "ld-audio-out prio=20 nice=0") any CPU burst of proot/Chromium longer than that is a click.
        urgentPriority = try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); true
        } catch (_: Exception) { false }
        var failures = 0
        while (active) {
            var t: AudioTrack? = null
            try {
                val s = Socket()
                socket = s
                s.tcpNoDelay = true
                s.connect(InetSocketAddress("127.0.0.1", p), 2_000)
                s.soTimeout = 5_000
                if (failures > 0) log("connected after $failures retries")
                failures = 0
                while (active) {
                    val voice = voiceMode
                    val sm = smooth
                    t = newTrack(voice, sm).also { it.play() }
                    track = t
                    pump(s.getInputStream(), t, voice, sm)   // returns only when the mic or Smooth flips the mode
                    retire(t)
                    t = null
                }
            } catch (e: Exception) {
                if (active && (failures == 0 || failures % 30 == 0)) log("stream not available (${e.javaClass.simpleName}); retrying")
                failures++
            } finally {
                retire(t)
                try { socket?.close() } catch (_: Exception) {}
            }
            if (!active) break
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
        }
    }

    private fun pump(input: InputStream, track: AudioTrack, voice: Boolean, sm: Boolean) {
        val buf = ByteArray(CHUNK * 2)
        var carry = 0                       // keep writes frame-aligned
        val guard = BacklogGuard(MAX_BACKLOG, CHUNK * 4, FRAME_BYTES)
        var nextStats = System.currentTimeMillis() + STATS_EVERY_MS
        var lastUnder = underruns()
        var lastDrops = drops
        while (active) {
            if (carry == 0 && (voiceMode != voice || smooth != sm)) return   // frame boundary: the caller rebuilds
            val backlog = input.available()
            val now = System.currentTimeMillis()
            if (carry == 0) {                              // only on a frame boundary, so channels never swap
                val drop = guard.feed(backlog, now)
                if (drop > 0) { drops++; droppedBytes += input.skip(drop.toLong()) }
            }
            if (now >= nextStats) {
                nextStats = now + STATS_EVERY_MS
                val burst = guard.takeMaxBurst()
                if (burst > maxBurstBytes) maxBurstBytes = burst
                val u = underruns()
                // Log only when something went wrong, so a quiet session stays quiet in sys.logs.
                if (u != lastUnder || drops != lastDrops) log("stats: underruns +${u - lastUnder} (total $u), drops +${drops - lastDrops}, burst ${burst / (RATE / 1000 * FRAME_BYTES)} ms")
                lastUnder = u; lastDrops = drops
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
