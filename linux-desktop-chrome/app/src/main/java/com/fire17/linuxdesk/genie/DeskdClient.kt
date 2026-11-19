package com.fire17.linuxdesk.genie

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's ONE persistent connection to deskd in the guest (spec 01 §2, 02 #5). Authed with the per-session
 * key as `{"auth":"<key>","genie":"app"}`. Every genie connection's Linux verbs share it: each call gets a
 * fresh relay-local `id`, so ids from different genie connections never collide, and the Router maps the
 * reply back to the caller's own id. Frames deskd sends without an `id` (stream frames, process exits) go to
 * the stream listener.
 *
 * The transport is behind [connect] (02 #5): TCP 127.0.0.1:8765 in v1; a guest Unix socket later swaps only
 * that function. It reconnects with backoff while running. The key is never logged.
 */
class DeskdClient(
    private val port: Int = 8765,
    private val log: (String) -> Unit = {},
) : Relay {

    private class Conn(val sock: Socket, val input: InputStream, val output: OutputStream)

    private class Pending(val onReply: (DecodedReply) -> Unit, var timeout: ScheduledFuture<*>?)

    @Volatile private var key: String? = null
    @Volatile private var conn: Conn? = null
    @Volatile private var running = false
    @Volatile private var caps: List<String> = emptyList()
    @Volatile private var streamListener: ((DecodedReply) -> Unit)? = null
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextId = AtomicLong(1)
    private val writeLock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor(Router.daemon("deskd-timer"))
    private var thread: Thread? = null

    /** Called with each new caps list after (re)connect, so the runtime can refresh notifications/UI. */
    @Volatile var onState: ((Boolean) -> Unit)? = null

    // -------------------------------------------------------------------------------- lifecycle

    /**
     * (Re)points the relay at a freshly started deskd with its new per-session key, and runs it. A live
     * connection to the previous deskd is dropped (its pending calls get `not_ready`). Repeatable.
     */
    @Synchronized
    fun start(newKey: String) {
        key = newKey
        dropConnection("restarted")
        if (running) return
        running = true
        thread = Thread({ loop() }, "deskd-relay").apply { isDaemon = true; start() }
    }

    /** Stops relaying (deskd stopped / Genie access OFF). Repeatable; [start] may be called again. */
    @Synchronized
    fun stop() {
        running = false
        key = null
        dropConnection("stopped")
        thread?.interrupt()
        thread = null
    }

    /** Final teardown (runtime stop). */
    fun close() {
        stop()
        timer.shutdownNow()
    }

    /** One-file transport seam (02 #5). Loopback only, never another address. */
    private fun connect(): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000)
        return s
    }

    private fun loop() {
        var backoff = 500L
        while (running) {
            var s: Socket? = null
            try {
                val k = key ?: throw IllegalStateException("no key")
                s = connect()
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                output.write(Frame.encodeRequest(JSONObject().put("auth", k).put("genie", "app").put("proto", Router.PROTO)))
                output.flush()
                val hello = Frame.decodeReply(Frame.readPayload(input, Frame.MAX_OUT))
                    ?: throw IllegalStateException("bad hello")
                if (!hello.header.optBoolean("ok", false)) {
                    log("deskd refused the app key (${hello.header.optString("error").take(32)})")
                    throw IllegalStateException("refused")
                }
                s.soTimeout = 0
                if (!running || key != k) throw IllegalStateException("rekeyed")
                caps = capsOf(hello.header)
                conn = Conn(s, input, output)
                backoff = 500L
                log("deskd relay connected; caps=${caps.joinToString(",")}")
                notifyState(true)
                refreshCapsLater()
                readLoop(input)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (running && conn != null) log("deskd relay lost: ${t.javaClass.simpleName}")
            }
            dropConnection("disconnected")
            try { s?.close() } catch (_: Exception) {}
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(3000L)
        }
    }

    private fun readLoop(input: InputStream) {
        while (running) {
            val rep = Frame.decodeReply(Frame.readPayload(input, Frame.MAX_OUT)) ?: continue
            val h = rep.header
            if (h.has("id")) {
                val id = h.optLong("id", -1L)
                val p = pending.remove(id) ?: continue          // a late reply after our timeout: drop it
                p.timeout?.cancel(false)
                h.remove("id")
                try { p.onReply(rep) } catch (_: Throwable) {}
            } else if (h.has("sid")) {
                try { streamListener?.invoke(rep) } catch (_: Throwable) {}
            }
        }
    }

    private fun dropConnection(reason: String) {
        val c = conn
        conn = null
        caps = emptyList()
        if (c != null) {
            try { c.sock.close() } catch (_: Exception) {}
            notifyState(false)
        }
        // Every caller gets exactly one answer.
        for (id in pending.keys.toList()) {
            val p = pending.remove(id) ?: continue
            p.timeout?.cancel(false)
            try { p.onReply(errorReply("not_ready", "deskd $reason")) } catch (_: Throwable) {}
        }
    }

    private fun notifyState(up: Boolean) { try { onState?.invoke(up) } catch (_: Throwable) {} }

    /** The browser comes up after deskd: re-read caps a few times so `browser` appears without a reconnect. */
    private fun refreshCapsLater() {
        for (delay in longArrayOf(3_000, 10_000, 30_000, 60_000)) {
            try {
                timer.schedule(Runnable { refreshCaps() }, delay, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
        }
    }

    fun refreshCaps() {
        if (conn == null) return
        call(JSONObject().put("cmd", "sys.caps"), 5_000) { r ->
            if (!r.header.has("error")) {
                val before = caps
                caps = capsOf(r.header)
                if (before != caps) notifyState(true)
            }
        }
    }

    private fun capsOf(o: JSONObject): List<String> {
        val a = o.optJSONArray("caps") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    // -------------------------------------------------------------------------------- Relay

    override fun ready(): Boolean = conn != null

    override fun caps(): List<String> = caps

    override fun setStreamListener(l: ((DecodedReply) -> Unit)?) { streamListener = l }

    override fun call(cmd: JSONObject, timeoutMs: Long, onReply: (DecodedReply) -> Unit) {
        val c = conn
        if (c == null) { onReply(errorReply("not_ready", "deskd is not up")); return }
        val id = nextId.getAndIncrement()
        val p = Pending(onReply, null)
        pending[id] = p
        p.timeout = try {
            timer.schedule(Runnable {
                pending.remove(id)?.let { try { it.onReply(errorReply("timeout", "deskd did not answer")) } catch (_: Throwable) {} }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
        val frame = try {
            Frame.encodeRequest(JSONObject(cmd.toString()).put("id", id))
        } catch (_: Exception) {
            pending.remove(id)?.let { it.timeout?.cancel(false); it.onReply(errorReply("too_big", "request over 1 MiB")) }
            return
        }
        if (!send(c, frame)) {
            pending.remove(id)?.let { it.timeout?.cancel(false); it.onReply(errorReply("not_ready", "deskd write failed")) }
        }
    }

    override fun post(cmd: JSONObject) {
        val c = conn ?: return
        try { send(c, Frame.encodeRequest(JSONObject(cmd.toString()).apply { remove("id") })) } catch (_: Exception) {}
    }

    private fun send(c: Conn, frame: ByteArray): Boolean = try {
        synchronized(writeLock) {
            c.output.write(frame)
            c.output.flush()
        }
        true
    } catch (_: Exception) {
        try { c.sock.close() } catch (_: Exception) {}     // the reader sees the close and reconnects
        false
    }

    private fun errorReply(code: String, detail: String) =
        DecodedReply(JSONObject().put("error", code).put("detail", detail), null)
}
