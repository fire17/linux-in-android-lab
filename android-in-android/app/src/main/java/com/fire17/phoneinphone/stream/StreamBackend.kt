package com.fire17.phoneinphone.stream

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.fire17.phoneinphone.BuildConfig
import com.fire17.phoneinphone.backend.BackendListener
import com.fire17.phoneinphone.backend.BackendStats
import com.fire17.phoneinphone.backend.PhoneBackend
import com.fire17.phoneinphone.backend.Stage
import com.fire17.phoneinphone.backend.StageStatus
import com.fire17.phoneinphone.backend.TouchAction
import dadb.AdbShellPacket
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import dadb.SyncResult
import okio.source
import java.io.DataInputStream
import java.io.EOFException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend "Stream": a whole real Android (redroid container / emulator / spare phone) running
 * elsewhere, rendered into our Surface through the scrcpy protocol.
 *
 * Pipeline:
 *   dadb (in-app ADB client) -> push scrcpy-server.jar -> app_process Server (tunnel_forward)
 *   -> localabstract:scrcpy_<scid> video stream -> MediaCodec -> Surface
 *   <- control stream <- touch/keys/text from the shell
 */
class StreamBackend(
    private val context: Context,
    private val target: LinkTarget,
    private val options: StreamOptions,
) : PhoneBackend {

    override val id = "stream"
    override val displayName = "Stream (scrcpy)"
    override val rendersVideo = true

    private val running = AtomicBoolean(false)
    private var listener: BackendListener? = null
    private var worker: Thread? = null

    private var dadb: Dadb? = null
    private var serverShell: AdbShellStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private val controlQueue = LinkedBlockingQueue<ByteArray>()

    private val surfaceLock = Any()
    @Volatile private var surface: Surface? = null
    @Volatile private var decoder: VideoDecoder? = null
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var mime: String = ""

    // stats
    @Volatile private var bytesTotal = 0L
    @Volatile private var lastStatT = System.nanoTime()
    @Volatile private var lastStatBytes = 0L
    @Volatile private var lastStatFrames = 0L
    @Volatile private var fps = 0f
    @Volatile private var kbps = 0f
    @Volatile private var deviceName = ""

    override fun start(listener: BackendListener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        worker = Thread({ runSafely() }, "pip-stream").also { it.start() }
    }

    private fun stage(key: String, title: String, status: StageStatus, detail: String = "") =
        listener?.onStage(Stage(key, title, status, detail))

    private fun log(s: String) {
        Log.i(TAG, s)
        listener?.onLog(s)
    }

    private fun runSafely() {
        var error: Throwable? = null
        try {
            run()
        } catch (t: Throwable) {
            if (running.get()) {
                error = t
                log("FATAL: ${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            cleanup()
            listener?.onStopped(error)
        }
    }

    private fun run() {
        val version = BuildConfig.SCRCPY_VERSION

        // 1. server asset present in the APK?
        stage("asset", "scrcpy-server $version bundled", StageStatus.RUNNING)
        val assetSize = try {
            context.assets.openFd(SERVER_ASSET).use { it.length }
        } catch (t: Throwable) {
            // openFd fails for compressed assets; fall back to reading
            try { context.assets.open(SERVER_ASSET).use { it.available().toLong() } } catch (t2: Throwable) { -1L }
        }
        if (assetSize <= 0) {
            stage("asset", "scrcpy-server $version bundled", StageStatus.FAIL,
                "assets/$SERVER_ASSET missing — CI must run scripts/fetch-scrcpy-server.sh")
            throw IllegalStateException("scrcpy-server asset missing")
        }
        stage("asset", "scrcpy-server $version bundled", StageStatus.OK, "$assetSize bytes")

        // 2. ADB connect
        val where = "${target.mode} ${target.host}:${target.port}" +
            if (target.serial.isNotBlank()) " (${target.serial})" else ""
        stage("adb", "ADB connect", StageStatus.RUNNING, where)
        val (d: Dadb, ident: AdbShellResponse) = try {
            val conn = AdbLink.connect(context, target)
            dadb = conn
            conn to conn.shell(
                "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; wm size"
            )
        } catch (t: Throwable) {
            stage("adb", "ADB connect", StageStatus.FAIL,
                "$where: ${t.javaClass.simpleName}: ${t.message}. Is adbd reachable (tailnet IP, port open)? " +
                    "First connect to a phone/emulator needs you to tap 'Allow' on the guest.")
            throw t
        }
        if (ident.exitCode != 0) {
            stage("adb", "ADB connect", StageStatus.FAIL, ident.allOutput.trim())
            throw IllegalStateException("guest shell failed: ${ident.allOutput}")
        }
        val lines = ident.output.trim().lines()
        stage("adb", "ADB connect", StageStatus.OK,
            "$where → ${lines.getOrNull(0)} / Android ${lines.getOrNull(1)} (SDK ${lines.getOrNull(2)}) ${lines.getOrNull(3) ?: ""}")
        val guestSdk = lines.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        if (guestSdk in 1..20) log("WARNING: scrcpy needs guest API 21+, guest reports $guestSdk")

        // 3. push server
        stage("push", "Push server to guest", StageStatus.RUNNING)
        context.assets.open(SERVER_ASSET).use { input ->
            // mode = S_IFREG | 0644 (adb sync sends st_mode incl. file type)
            val res = d.push(input.source(), ScrcpyProtocol.DEVICE_SERVER_PATH, 0x81A4, System.currentTimeMillis())
            if (res is SyncResult.Failure) {
                stage("push", "Push server to guest", StageStatus.FAIL, res.reason)
                throw IllegalStateException("push failed: ${res.reason}")
            }
        }
        stage("push", "Push server to guest", StageStatus.OK, ScrcpyProtocol.DEVICE_SERVER_PATH)

        // 4. launch server (shell stays open for the server's lifetime)
        val scid = ScrcpyProtocol.randomScid()
        val cmd = ScrcpyProtocol.serverCommand(version, scid, options)
        stage("server", "Launch scrcpy-server", StageStatus.RUNNING, "scid=%08x".format(scid))
        log("guest$ $cmd")
        val shell = d.openShell(cmd)
        serverShell = shell
        Thread({ pumpServerOutput(shell) }, "pip-server-log").start()

        // 5. sockets: video first (carries the dummy byte), then control
        val sock = ScrcpyProtocol.socketName(scid)
        val video = try {
            AdbLink.openAbstract(d, sock, expectDummyByte = true)
        } catch (t: Throwable) {
            stage("server", "Launch scrcpy-server", StageStatus.FAIL,
                "no localabstract:$sock — server died? Open the log: a version mismatch prints there")
            throw t
        }
        videoStream = video
        val control = AdbLink.openAbstract(d, sock, expectDummyByte = false)
        controlStream = control
        stage("server", "Launch scrcpy-server", StageStatus.OK, "connected to localabstract:$sock")

        Thread({ controlWriterLoop(control) }, "pip-control-out").start()
        Thread({ controlReaderLoop(control) }, "pip-control-in").start()

        // 6. stream header
        val vin = DataInputStream(video.source.inputStream())
        deviceName = ScrcpyProtocol.readDeviceName(vin)
        val codecId = vin.readInt()
        log("device=\"$deviceName\" codec=${ScrcpyProtocol.codecName(codecId)}")
        mime = VideoDecoder.mimeFor(codecId)
        stage("video", "Video stream", StageStatus.RUNNING, "${ScrcpyProtocol.codecName(codecId)} from $deviceName")

        // 7. decode loop
        //  - A Session packet = new encoder session (start, rotation, resize): the server always
        //    follows it with a CONFIG packet and a key frame, so we just rebuild the decoder.
        //  - If the decoder is rebuilt for a NEW SURFACE mid-session, we re-feed the last CONFIG
        //    and ask for a fresh key frame with RESET_VIDEO (that produces a new Session packet,
        //    which rebuilds once more without asking again: no loop).
        var firstFrame = true
        while (running.get()) {
            val pkt = try { ScrcpyProtocol.readVideoPacket(vin) } catch (e: EOFException) {
                throw IllegalStateException("video stream closed by guest", e)
            }
            when (pkt) {
                is ScrcpyProtocol.VideoPacket.Session -> {
                    log("session ${pkt.width}x${pkt.height}${if (pkt.clientResize) " (client resize)" else ""}")
                    videoW = pkt.width
                    videoH = pkt.height
                    lastConfig = null
                    listener?.onVideoSize(pkt.width, pkt.height)
                    recreateDecoder(requestKeyFrame = false)
                }
                is ScrcpyProtocol.VideoPacket.Media -> {
                    bytesTotal += pkt.data.size + 12
                    if (pkt.config) lastConfig = pkt.data
                    // Locked: the UI thread may release/recreate the decoder when the surface changes.
                    val fed = synchronized(surfaceLock) {
                        val dec = decoder
                        if (dec == null) false else try {
                            dec.queue(pkt.data, pkt.ptsUs, pkt.config); true
                        } catch (e: IllegalStateException) {
                            log("decoder error (${e.message}); rebuilding on next key frame")
                            dec.release(); decoder = null; false
                        }
                    }
                    if (fed) {
                        if (firstFrame && !pkt.config) {
                            firstFrame = false
                            stage("video", "Video stream", StageStatus.OK,
                                "${ScrcpyProtocol.codecName(codecId)} ${videoW}x$videoH from $deviceName")
                        }
                    }
                    updateStats()
                }
            }
        }
    }

    @Volatile private var lastConfig: ByteArray? = null

    private fun recreateDecoder(requestKeyFrame: Boolean) {
        synchronized(surfaceLock) {
            decoder?.release()
            decoder = null
            val s = surface ?: return
            if (videoW <= 0 || videoH <= 0 || mime.isEmpty()) return
            try {
                val dec = VideoDecoder(mime, videoW, videoH, s).also { it.start() }
                decoder = dec
                if (requestKeyFrame) {
                    lastConfig?.let { dec.queue(it, 0, true) }
                    controlQueue.offer(ScrcpyProtocol.simple(ScrcpyProtocol.TYPE_RESET_VIDEO))
                }
            } catch (t: Throwable) {
                log("decoder init failed: ${t.message}")
            }
        }
    }

    private fun updateStats() {
        val now = System.nanoTime()
        val dt = (now - lastStatT) / 1e9f
        if (dt >= 1f) {
            val frames = decoder?.framesRendered ?: 0L
            fps = ((frames - lastStatFrames).coerceAtLeast(0)) / dt
            kbps = (bytesTotal - lastStatBytes) * 8f / 1000f / dt
            lastStatFrames = frames
            lastStatBytes = bytesTotal
            lastStatT = now
        }
    }

    private fun pumpServerOutput(shell: AdbShellStream) {
        try {
            while (running.get()) {
                when (val p = shell.read()) {
                    is AdbShellPacket.StdOut -> String(p.payload).lines().filter { it.isNotBlank() }.forEach { log("[server] $it") }
                    is AdbShellPacket.StdError -> String(p.payload).lines().filter { it.isNotBlank() }.forEach { log("[server!] $it") }
                    is AdbShellPacket.Exit -> { log("[server] exited ${p.payload.firstOrNull()}"); return }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) log("[server] log pipe closed: ${t.message}")
        }
    }

    private fun controlWriterLoop(control: AdbStream) {
        try {
            while (running.get()) {
                val msg = controlQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                control.sink.write(msg)
                // coalesce whatever else is queued, then flush once
                while (true) {
                    val more = controlQueue.poll() ?: break
                    control.sink.write(more)
                }
                control.sink.flush()
            }
        } catch (t: Throwable) {
            if (running.get()) log("control writer stopped: ${t.message}")
        }
    }

    /** Device -> client messages: CLIPBOARD(0), ACK_CLIPBOARD(1), UHID_OUTPUT(2). Drained so adb never stalls. */
    private fun controlReaderLoop(control: AdbStream) {
        try {
            val din = DataInputStream(control.source.inputStream())
            while (running.get()) {
                when (val type = din.readUnsignedByte()) {
                    0 -> { val n = din.readInt(); val b = ByteArray(n); din.readFully(b); log("guest clipboard: ${String(b).take(80)}") }
                    1 -> { din.readLong() }
                    2 -> { din.readUnsignedShort(); val n = din.readUnsignedShort(); din.skipBytes(n) }
                    else -> { log("unknown device msg type $type; stopping control reader"); return }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) log("control reader stopped: ${t.message}")
        }
    }

    override fun setSurface(surface: Surface?) {
        synchronized(surfaceLock) {
            this.surface = surface
            if (surface == null) {
                decoder?.release()
                decoder = null
            }
        }
        if (surface != null && running.get() && videoW > 0) recreateDecoder(requestKeyFrame = true)
    }

    override fun sendTouch(action: TouchAction, pointerId: Long, x: Float, y: Float, pressure: Float) {
        val w = videoW
        val h = videoH
        if (w <= 0 || h <= 0) return
        val a = when (action) {
            TouchAction.DOWN -> ScrcpyProtocol.MOTION_ACTION_DOWN
            TouchAction.UP -> ScrcpyProtocol.MOTION_ACTION_UP
            TouchAction.MOVE -> ScrcpyProtocol.MOTION_ACTION_MOVE
        }
        val px = (x.coerceIn(0f, 1f) * w).toInt().coerceAtMost(w - 1)
        val py = (y.coerceIn(0f, 1f) * h).toInt().coerceAtMost(h - 1)
        val p = if (action == TouchAction.UP) 0f else pressure
        controlQueue.offer(ScrcpyProtocol.injectTouch(a, pointerId, px, py, w, h, p))
    }

    override fun sendKey(action: Int, keyCode: Int, metaState: Int) {
        controlQueue.offer(ScrcpyProtocol.injectKeycode(action, keyCode, 0, metaState))
    }

    /**
     * ASCII goes through INJECT_TEXT (key-event synthesis on the guest). Anything else — Hebrew,
     * emoji — goes through SET_CLIPBOARD(paste=true), because INJECT_TEXT can only type characters
     * present in the guest's virtual key character map (scrcpy doc/keyboard.md).
     */
    override fun sendText(text: String) {
        if (text.isEmpty()) return
        if (ScrcpyProtocol.isAscii(text)) {
            controlQueue.offer(ScrcpyProtocol.injectText(text))
        } else {
            controlQueue.offer(ScrcpyProtocol.setClipboard(0L, text, paste = true))
        }
    }

    fun sendSystemKey(keyCode: Int) {
        sendKey(KeyEvent.ACTION_DOWN, keyCode)
        sendKey(KeyEvent.ACTION_UP, keyCode)
    }

    override fun stats() = BackendStats(
        fps = fps,
        kbps = kbps,
        videoWidth = videoW,
        videoHeight = videoH,
        framesDecoded = decoder?.framesRendered ?: 0,
        extra = deviceName,
    )

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        // stop() is called from Activity.onDestroy (main thread). Closing dadb streams WRITES an
        // A_CLSE packet to the socket, which throws NetworkOnMainThreadException there and (inside
        // dadb's AdbConnection.close) skips closing the socket — leaking the worker thread and the
        // guest server. So tear down off the main thread.
        Thread({ cleanup(); worker?.interrupt() }, "pip-stream-stop").start()
    }

    private fun cleanup() {
        running.set(false)
        try { videoStream?.close() } catch (ignored: Throwable) {}
        try { controlStream?.close() } catch (ignored: Throwable) {}
        try { serverShell?.close() } catch (ignored: Throwable) {}
        synchronized(surfaceLock) {
            decoder?.release()
            decoder = null
        }
        try { dadb?.close() } catch (ignored: Throwable) {}
        videoStream = null; controlStream = null; serverShell = null; dadb = null
    }

    companion object {
        private const val TAG = "PiP.Stream"
        const val SERVER_ASSET = "scrcpy-server.jar"
    }
}
