package com.fire17.linuxdesk.genie

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A framed, bidirectional pipe to ONE genie connection (an iroh bi-stream, or a loopback TCP socket). */
interface Wire {
    /** The next request payload (no length prefix). Throws [FrameTooBig], EOFException or IOException. */
    fun read(): ByteArray
    /** Writes one already-encoded frame (length prefix included). May block (that is the backpressure). */
    fun write(frame: ByteArray)
    fun close()
}

/** deskd as the Router sees it: DeskdClient in production, a fake in RouterTest. */
interface Relay {
    fun ready(): Boolean
    /** Verb families deskd reported as up (its hello / sys.caps). */
    fun caps(): List<String>
    /**
     * Sends [cmd] to deskd under a relay-chosen id (the caller's `id` is ignored) and calls [onReply]
     * EXACTLY once: with deskd's reply, or with a synthesized `timeout` / `not_ready` error.
     * [onReply] runs on the relay's reader thread and must not block.
     */
    fun call(cmd: JSONObject, timeoutMs: Long, onReply: (DecodedReply) -> Unit)
    /** Fire-and-forget, no reply expected (stream.ack). */
    fun post(cmd: JSONObject)
    /** Frames deskd sends without an `id`: stream frames (`sid` + `seq`) and process exits (`sid` + `exit`). */
    fun setStreamListener(l: ((DecodedReply) -> Unit)?)
}

/** The answer to a connection's first frame (mapped from Binding.Auth by GenieRuntime). */
sealed class HelloResult {
    data class Ok(val genie: String, val newToken: String?) : HelloResult()
    data class Refused(val error: String) : HelloResult()
}

/** Everything the Router needs from Android, behind one seam so RouterTest runs on the plain JVM. */
interface RouterEnv {
    fun authenticate(given: String, genie: String): HelloResult
    fun isBound(): Boolean
    fun generation(): Long
    /** The genie window (02 §03: SG's on-screen-or-2-min rule by default, or FGS). */
    fun windowOpen(): Boolean
    /** refuse_while_locked is ON and the device is locked (02 §03, default OFF). */
    fun lockedRefusal(): Boolean
    /** The All-files XOR Genie-shell guard blocks shell right now (02 §03, default OFF). */
    fun xorBlocks(): Boolean
    fun fs(cmd: JSONObject): Reply
    fun fsAvailable(): Boolean = true
    /** Storage state for sys.caps / sys.ping: {fs_mode, all_files_granted, xor_guard, roots} (StoragePrefs.capsInfo). */
    fun fsInfo(): JSONObject = JSONObject()
    /** Read-only hardware detection for sys.caps (HwProbe.get: device/soc/gpu/vulkan/npu hints/cameras). No identifiers. */
    fun hwInfo(): JSONObject = JSONObject()
    /**
     * `wide_window_gate` (prefs ld.genie, default false): when true the genie window gates every acting verb
     * ([Router.WIDE_GATED]); when false (the default) it gates only Storage Genie's three ([Router.SG_GATED]).
     */
    fun wideWindowGate(): Boolean = false
    /** `session` object of sys.ping: rootfs, packages, x11, browser (the Router adds deskd). */
    fun sessionInfo(): JSONObject = JSONObject()
    /** `x` object of sys.ping: w, h, dpi, density. */
    fun xInfo(): JSONObject = JSONObject()
    /** sys.ota.status (read-only): {active, prev, highest, trial, auto_apply, ledger:[tail]}. Default = OTA not wired. */
    fun otaStatus(tail: Int): JSONObject = JSONObject().put("ok", false).put("error", "unsupported")
    /** sys.ota.check: checks the update URL (signature, anti-downgrade); NEVER downloads the bundle or installs. */
    fun otaCheck(): JSONObject = JSONObject().put("ok", false).put("error", "unsupported")
    /**
     * sys.cameras (cameras v4): the phone's camera inventory as CameraDiag JSON — ids, facing, focal, logical/physical
     * lenses, max sizes, which ones "Phone camera for Linux" exposes and the default. READ-ONLY: never opens a camera,
     * needs no CAMERA permission, and does not depend on the in-app switch. Default = not wired.
     */
    fun camerasInfo(): JSONObject = JSONObject().put("ok", false).put("error", "unsupported")
    /**
     * sys.perf (ld-perf): READ-ONLY performance snapshot — top CPU users among the app's own processes (app, X server,
     * proot, Chromium, PulseAudio), MemAvailable, sound underruns/drops. Blocks ~1 s (a /proc sample). Default = not wired.
     */
    fun perfInfo(): JSONObject = JSONObject().put("ok", false).put("error", "unsupported")
    fun version(): String
    fun now(): Long = System.currentTimeMillis()
    fun onAppClosed() {}
    fun onConnected(genie: String, paired: Boolean) {}
    fun onCommand(genie: String, at: Long) {}
    fun onDisconnected(liveSessions: Int) {}
    /** Connection-level events only. Never called with auth values, arguments or typed text. */
    fun log(line: String) {}
}

/**
 * `filesDir/genie-audit.jsonl` (spec 01 §4 rule 10): one row per command — time, verb, ok/error code,
 * duration, transport. NEVER arguments, typed text, stdin, paths or file contents. Append-only, rotated to
 * `.1` at [maxBytes]. Pure JVM.
 */
class Audit(private val file: File, private val maxBytes: Long = 5L shl 20) {

    @Synchronized
    fun record(t: Long, cmd: String, ok: Boolean, err: String?, ms: Long, via: String) {
        val row = JSONObject().put("t", t).put("cmd", safeVerb(cmd)).put("ok", ok).put("ms", ms).put("via", via)
        if (!ok && err != null) row.put("error", safeVerb(err))
        val line = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
        try {
            file.parentFile?.mkdirs()
            if (file.length() + line.size > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            java.io.FileOutputStream(file, true).use { it.write(line) }
        } catch (_: Exception) {
            // An audit failure must never break a command.
        }
    }

    /** Rows with t >= [since], oldest first, at most [limit] (the newest ones). */
    @Synchronized
    fun query(since: Long, limit: Int = 1000): JSONObject {
        val keep = ArrayDeque<JSONObject>()
        var total = 0
        for (f in listOf(File(file.path + ".1"), file)) {
            if (!f.isFile) continue
            try {
                f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (l in lines) {
                        val o = try { JSONObject(l) } catch (_: Exception) { continue }
                        if (o.optLong("t") < since) continue
                        total++
                        keep.addLast(o)
                        if (keep.size > limit) keep.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }
        val rows = JSONArray()
        keep.forEach { rows.put(it) }
        return JSONObject().put("ok", true).put("rows", rows).put("total", total).put("truncated", total > keep.size)
    }

    private fun safeVerb(s: String): String {
        val t = s.take(64)
        return if (t.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }) t else "?"
    }
}

/**
 * The ONE dispatcher behind both transports (IrohLink and WireServer): the hello/auth, the verb table, the
 * error codes, the genie-window gates, the limits, the stream ack window, `sys.*` and the audit log.
 * `fs.*` goes to FsVerbs (via [RouterEnv.fs]); `shell/input/screen/browser/stream.*` go to deskd (via [Relay]).
 */
class Router(
    private val env: RouterEnv,
    private val relay: Relay?,
    private val audit: Audit?,
    private val pool: ExecutorService = Executors.newCachedThreadPool(daemon("genie-verb")),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon("genie-timer")),
    /** The session log `sys.logs` reads (SessionBus writes the process-wide one). */
    private val logs: LogRing? = SessionLog.ring,
) {
    companion object {
        const val PROTO = "linuxdesk/1"
        const val MAX_SESSIONS = 4
        const val MAX_PENDING_HELLOS = 8
        const val MAX_INFLIGHT = 16           // = deskd's per-connection cap
        const val MAX_STREAMS = 2
        const val MAX_SPAWNS = 4
        const val ACK_WINDOW = 4
        const val MAX_EXEC_S = 600
        const val MAX_PROPOSAL_PATHS = 200
        const val MAX_QUEUED_BYTES = 48L shl 20
        const val HELLO_TIMEOUT_MS = 10_000L

        val LINUX_FAMILIES = setOf("shell", "input", "screen", "browser", "stream")

        /** The genie-facing Linux surface is exactly spec 01 §1.3. Anything else deskd has is not relayed. */
        val LINUX_VERBS = setOf(
            "shell.exec", "shell.spawn", "shell.write", "shell.kill",
            "input.move", "input.click", "input.down", "input.up", "input.drag", "input.scroll", "input.type",
            "input.key", "input.keydown", "input.keyup", "input.windows", "input.activate",
            "screen.info", "screen.capture",
            "browser.tabs", "browser.open", "browser.navigate", "browser.activate", "browser.close", "browser.tree",
            "browser.find", "browser.click", "browser.type", "browser.key", "browser.scroll", "browser.eval",
            "browser.wait",
            "stream.start", "stream.ack", "stream.stop",
        )

        /**
         * The genie window gate (02 §03: Storage Genie's behaviour is the default). By default only SG's three
         * gated verbs need the app on screen (or within 2 min); the Linux machine stays under the genie's control.
         */
        val SG_GATED = setOf("fs.scan", "fs.propose_delete", "fs.execute")

        /**
         * Used only when the `wide_window_gate` setting is ON (default OFF): every acting verb is gated, except
         * stopping things and the read-only proposal/log verbs below.
         */
        val UNGATED = setOf("stream.ack", "stream.stop", "shell.kill", "fs.proposal", "fs.cancel", "fs.adjust", "fs.log")

        /** mic.*, audio.in*, audio.mic*, microphone.* — never served over the link (MicIn consent rule). */
        fun isMicVerb(verb: String): Boolean {
            val v = verb.lowercase()
            return v.startsWith("mic.") || v.startsWith("microphone.") || v.startsWith("audio.in") ||
                v.startsWith("audio.mic") || v.startsWith("audio.record")
        }

        /** camera.*, cam.* — never served over the link (the phone camera is the in-app switch only). */
        fun isCameraVerb(verb: String): Boolean {
            val v = verb.lowercase()
            return v.startsWith("camera.") || v.startsWith("cam.")
        }

        fun windowGated(verb: String, wide: Boolean): Boolean =
            if (wide) !verb.startsWith("sys.") && verb !in UNGATED else verb in SG_GATED

        /** Verbs that address an existing sid, which must belong to the asking connection. */
        private val SID_VERBS = setOf("shell.write", "shell.kill", "stream.stop", "stream.ack")

        fun daemon(name: String) = java.util.concurrent.ThreadFactory { r ->
            Thread(r, name).apply { isDaemon = true }
        }

        private fun err(code: String, detail: String? = null): Reply = Reply.error(code, detail)
        private fun errorOf(r: Reply): String? = when (r) {
            is Reply.Json -> if (r.obj.has("error")) r.obj.optString("error") else null
            is Reply.Binary -> if (r.header.has("error")) r.header.optString("error") else null
        }
    }

    private val sessions = CopyOnWriteArrayList<Session>()
    private val pendingHellos = AtomicInteger()
    private val owners = ConcurrentHashMap<String, StreamOwner>()

    init {
        relay?.setStreamListener { onStreamFrame(it) }
    }

    fun liveSessions(): Int = sessions.size

    /** Closes every genie connection, or only those of one transport ("iroh" / "tcp"). */
    fun closeAll(reason: String, via: String? = null) {
        for (s in sessions) if (via == null || s.via == via) s.close(reason)
    }

    fun shutdown() {
        closeAll("shutdown")
        relay?.setStreamListener(null)
        pool.shutdownNow()
        timer.shutdownNow()
    }

    fun caps(): List<String> {
        val c = mutableListOf("sys")
        if (env.fsAvailable()) c += "fs"
        if (relay != null && relay.ready()) {
            val up = relay.caps().toSet()
            for (f in listOf("shell", "input", "screen", "browser", "stream")) if (f in up) c += f
        }
        return c
    }

    // ------------------------------------------------------------------------------------------ serve

    /**
     * Runs one genie connection to the end (blocking; the transport gives it its own thread). The first frame
     * must authenticate; an unauthenticated frame gets exactly one error and a close (spec 01 §4 rule 3).
     */
    fun serve(wire: Wire, via: String) {
        if (pendingHellos.incrementAndGet() > MAX_PENDING_HELLOS || sessions.size >= MAX_SESSIONS) {
            pendingHellos.decrementAndGet()
            refuse(wire, JSONObject().put("error", "busy").put("detail", "too many connections"))
            return
        }
        val session = (try { hello(wire, via) } finally { pendingHellos.decrementAndGet() }) ?: return
        try {
            while (session.open) {
                val payload = try {
                    wire.read()
                } catch (_: FrameTooBig) {
                    session.enqueue(Frame.encodeJson(JSONObject().put("error", "too_big").put("detail", "request over 1 MiB")))
                    break
                } catch (_: Exception) {
                    break
                }
                handle(session, payload)
            }
        } finally {
            session.close("ended")
        }
    }

    /** Reads and answers the first frame. Returns the live session, or null after refusing and closing. */
    private fun hello(wire: Wire, via: String): Session? {
        val authed = AtomicBoolean(false)
        val watchdog = try {
            timer.schedule(Runnable { if (!authed.get()) safeClose(wire) }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
        try {
            val payload = try {
                wire.read()
            } catch (_: FrameTooBig) {
                refuse(wire, JSONObject().put("error", "too_big")); return null
            } catch (_: Exception) {
                safeClose(wire); return null
            }
            val hello = Frame.parseRequest(payload)
                ?: run { refuse(wire, JSONObject().put("error", "auth")); return null }
            val hid = Frame.requestId(hello)
            fun withHid(o: JSONObject) = if (hid != Frame.NO_ID && hid != Frame.BAD_ID) o.put("id", hid) else o
            if (hello.has("proto") && hello.optString("proto") != PROTO) {
                refuse(wire, withHid(JSONObject().put("error", "unsupported").put("detail", "proto must be $PROTO")))
                return null
            }
            val auth = try {
                env.authenticate(hello.optString("auth", ""), hello.optString("genie", ""))
            } catch (_: Exception) {
                HelloResult.Refused("internal")
            }
            return when (auth) {
                is HelloResult.Refused -> {
                    env.log("genie refused via $via: ${auth.error}")
                    refuse(wire, withHid(JSONObject().put("error", auth.error)))
                    null
                }
                is HelloResult.Ok -> {
                    authed.set(true)
                    val s = Session(wire, via, auth.genie, env.generation())
                    sessions += s
                    val r = JSONObject().put("ok", true).put("app", "linuxdesk").put("version", env.version())
                        .put("proto", PROTO).put("genie", auth.genie).put("caps", JSONArray(caps())).put("audio", false)
                    if (auth.newToken != null) r.put("paired", true).put("token", auth.newToken)
                    s.enqueue(Frame.encodeJson(withHid(r)))
                    env.log("genie connected via $via${if (auth.newToken != null) " (new pairing)" else ""}")
                    try { env.onConnected(auth.genie, auth.newToken != null) } catch (_: Exception) {}
                    s
                }
            }
        } finally {
            watchdog?.cancel(false)
        }
    }

    private fun refuse(wire: Wire, o: JSONObject) {
        try { wire.write(Frame.encodeJson(o)) } catch (_: Exception) {}
        try { Thread.sleep(300) } catch (_: InterruptedException) {}   // let the frame flush (as Link.kt)
        safeClose(wire)
    }

    private fun safeClose(wire: Wire) { try { wire.close() } catch (_: Exception) {} }

    // ------------------------------------------------------------------------------------------ dispatch

    private fun handle(s: Session, payload: ByteArray) {
        val t0 = env.now()
        val cmd = Frame.parseRequest(payload)
        if (cmd == null) {
            s.deliver(s.ticket(), Frame.encodeJson(JSONObject().put("error", "bad_json")))
            return
        }
        val id = Frame.requestId(cmd)
        if (id == Frame.BAD_ID) {
            s.deliver(s.ticket(), Frame.encodeJson(JSONObject().put("error", "bad_args").put("detail", "id must be an integer")))
            return
        }
        val verb = cmd.optString("cmd", "")

        // Forget in the app (or a new pairing) ends every live session on its next command.
        if (!env.isBound() || env.generation() != s.generation) {
            s.enqueue(Frame.encodeJson(Frame.copyWithId(JSONObject().put("error", "unpaired"), id)))
            s.close("unpaired")
            return
        }

        if (verb == "stream.ack") { onAck(s, cmd); return }   // no reply (spec 01 §1.3)

        if (s.inflight.get() >= MAX_INFLIGHT) {
            val f = Frame.encodeJson(Frame.copyWithId(JSONObject().put("error", "busy").put("detail", "too many commands in flight"), id))
            if (id == Frame.NO_ID) s.deliver(s.ticket(), f) else s.enqueue(f)
            return
        }

        val ticket = if (id == Frame.NO_ID) s.ticket() else -1L
        s.inflight.incrementAndGet()
        s.lastCommandAt = t0
        try { env.onCommand(s.genie, t0) } catch (_: Exception) {}

        val finished = AtomicBoolean(false)
        val done: (Reply) -> Unit = done@{ r ->
            if (!finished.compareAndSet(false, true)) return@done
            s.inflight.decrementAndGet()
            val code = errorOf(r)
            audit?.record(t0, verb, code == null, code, env.now() - t0, s.via)
            val frame = Frame.encode(Frame.withId(r, id))
            if (ticket >= 0) s.deliver(ticket, frame) else s.enqueue(frame)
        }
        try {
            dispatch(s, verb, cmd, done)
        } catch (t: Throwable) {
            done(err("internal", t.javaClass.simpleName))
        }
    }

    private fun dispatch(s: Session, verb: String, cmd: JSONObject, done: (Reply) -> Unit) {
        val family = verb.substringBefore('.', "")
        when {
            verb == "sys.ping" -> done(Reply.Json(ping(s)))
            verb == "sys.caps" -> done(Reply.Json(JSONObject().put("ok", true).put("caps", JSONArray(caps())).put("audio", false)
                .put("fs", try { env.fsInfo() } catch (_: Exception) { JSONObject() })
                .put("hw", try { env.hwInfo() } catch (_: Exception) { JSONObject() })))
            verb == "sys.audit" -> pool.execute {
                done(audit?.let { Reply.Json(it.query(cmd.optLong("since", 0L))) } ?: err("unsupported", "no audit log"))
            }
            // Read-only, like sys.audit: never window-gated (sys.* is outside both gate sets).
            verb == "sys.logs" -> pool.execute { done(SysLogs.answer(cmd, logs, audit)) }
            // OTA (read-only + check-only; installing is an in-app tap or the in-app auto-apply switch, never a verb).
            verb == "sys.ota.status" -> pool.execute {
                done(try { Reply.Json(env.otaStatus(cmd.optInt("n", 20).coerceIn(0, 100))) } catch (t: Throwable) { err("internal", t.javaClass.simpleName) })
            }
            verb == "sys.ota.check" -> pool.execute {
                done(try { Reply.Json(env.otaCheck()) } catch (t: Throwable) { err("internal", t.javaClass.simpleName) })
            }
            verb.startsWith("sys.ota.") -> done(err("denied", "OTA installs only from the LinuxDesk app"))
            // Read-only camera INVENTORY (no openCamera, no permission). Matched by exact name BEFORE the camera.* /
            // cam.* deny below, and "sys.cameras" does not start with either prefix, so capture stays denied.
            verb == "sys.cameras" -> pool.execute {
                done(try { Reply.Json(env.camerasInfo()) } catch (t: Throwable) { err("internal", t.javaClass.simpleName) })
            }
            // Read-only, like sys.logs (ld-perf): a /proc CPU sample + memory + sound counters. Changes nothing.
            verb == "sys.perf" -> pool.execute {
                done(try { Reply.Json(env.perfInfo()) } catch (t: Throwable) { err("internal", t.javaClass.simpleName) })
            }
            family == "fs" -> {
                gate(verb, family)?.let { done(it); return }
                val paths = cmd.optJSONArray("paths")
                if (verb == "fs.propose_delete" && paths != null && paths.length() > MAX_PROPOSAL_PATHS) {
                    done(err("bad_args", "at most $MAX_PROPOSAL_PATHS paths per proposal")); return
                }
                // `manual` means "the person did it in the app"; it is never accepted over the link (as SG).
                val c = JSONObject(cmd.toString()).apply { remove("manual"); remove("id"); remove("auth") }
                pool.execute {
                    done(try { env.fs(c) } catch (t: Throwable) { err("internal", t.javaClass.simpleName) })
                }
            }
            verb in LINUX_VERBS -> {
                gate(verb, family)?.let { done(it); return }
                relayCall(s, verb, cmd, done)
            }
            verb.startsWith("android.su.") -> done(err("unsupported", "real Android root is not part of v1"))
            // Microphone to Linux is the person's in-app switch only ("only if I approve and turn it on").
            // No verb may start, stop, or query capture; refused before any gate, never relayed to deskd.
            isMicVerb(verb) -> done(err("denied", "the microphone can only be turned on in the LinuxDesk app"))
            // Same rule for the phone camera: only the in-app switch "Phone camera for Linux" can turn it on.
            isCameraVerb(verb) -> done(err("denied", "the camera can only be turned on in the LinuxDesk app"))
            else -> done(err("unknown_cmd", verb.take(64)))
        }
    }

    /** The refusals that do not depend on arguments. Null = go ahead. */
    private fun gate(verb: String, family: String): Reply? {
        val wide = try { env.wideWindowGate() } catch (_: Exception) { false }
        if (windowGated(verb, wide) && !env.windowOpen()) {
            try { env.onAppClosed() } catch (_: Exception) {}
            return err("app_closed", "open LinuxDesk on the phone")
        }
        val stopper = verb == "shell.kill" || verb == "stream.stop"
        if (!stopper && (family == "shell" || family == "input") && env.lockedRefusal()) {
            return err("denied", "the phone is locked")
        }
        if (!stopper && family == "shell" && env.xorBlocks()) {
            return err("denied", "All-files access is on and the All-files XOR shell guard is set")
        }
        if (family == "fs" && !env.fsAvailable()) return err("not_ready", "storage is not available")
        if (family in LINUX_FAMILIES) {
            if (relay == null || !relay.ready()) return err("not_ready", "deskd is not up")
            if (!stopper && family !in relay.caps()) return err("not_ready", "$family is not up yet")
        }
        return null
    }

    private fun ping(s: Session): JSONObject {
        val session = try { env.sessionInfo() } catch (_: Exception) { JSONObject() }
        session.put("deskd", relay?.ready() == true)
        return JSONObject().put("ok", true).put("app", "linuxdesk").put("version", env.version()).put("proto", PROTO)
            .put("genie", s.genie).put("caps", JSONArray(caps())).put("audio", false)
            .put("window_open", env.windowOpen())
            .put("session", session)
            .put("x", try { env.xInfo() } catch (_: Exception) { JSONObject() })
            .put("fs", try { env.fsInfo() } catch (_: Exception) { JSONObject() })
    }

    // ------------------------------------------------------------------------------------------ relay

    private fun relayTimeoutMs(verb: String, cmd: JSONObject): Long = when (verb) {
        "shell.exec" -> (cmd.optDouble("timeout_s", 30.0).coerceIn(1.0, MAX_EXEC_S.toDouble()) * 1000).toLong() + 15_000
        "browser.open", "browser.navigate" -> 45_000
        "browser.wait" -> (cmd.optDouble("timeout_s", 10.0).coerceIn(0.0, MAX_EXEC_S.toDouble()) * 1000).toLong() + 15_000
        else -> 30_000
    }

    private fun relayCall(s: Session, verb: String, cmd: JSONObject, done: (Reply) -> Unit) {
        val r = relay ?: return done(err("not_ready", "deskd is not up"))

        if (verb == "shell.exec" && cmd.has("timeout_s")) {
            val v = cmd.opt("timeout_s")
            if (v !is Number || v.toDouble().isNaN() || v.toDouble() <= 0 || v.toDouble() > MAX_EXEC_S) {
                return done(err("bad_args", "timeout_s must be 1..$MAX_EXEC_S"))
            }
        }
        val sid = if (cmd.has("sid")) cmd.opt("sid")?.toString() else null
        if (verb in SID_VERBS && verb != "stream.ack") {
            if (sid == null || owners[sid]?.session !== s) return done(err("bad_args", "no stream with that sid"))
        }

        // Reserve a slot BEFORE asking deskd, so two concurrent starts cannot both pass the limit.
        val kind = cmd.optString("kind", "screen")
        val countsAsStream = verb == "stream.start"
        val countsAsSpawn = verb == "shell.spawn"
        if (countsAsStream && s.streamSlots.incrementAndGet() > MAX_STREAMS) {
            s.streamSlots.decrementAndGet(); return done(err("busy", "at most $MAX_STREAMS streams per session"))
        }
        if (countsAsSpawn && s.spawnSlots.incrementAndGet() > MAX_SPAWNS) {
            s.spawnSlots.decrementAndGet(); return done(err("busy", "at most $MAX_SPAWNS spawned processes"))
        }

        val out = JSONObject(cmd.toString()).apply { remove("id"); remove("auth") }
        r.call(out, relayTimeoutMs(verb, cmd)) { rep ->
            val h = rep.header
            val ok = !h.has("error")
            if (countsAsStream || countsAsSpawn) {
                val newSid = if (ok && h.has("sid")) h.opt("sid")?.toString() else null
                if (newSid != null && s.open) {
                    owners[newSid] = StreamOwner(s, droppable = countsAsStream && kind == "screen", spawn = countsAsSpawn)
                } else {
                    if (countsAsStream) s.streamSlots.decrementAndGet() else s.spawnSlots.decrementAndGet()
                    if (newSid != null) stopRemote(newSid, countsAsSpawn)   // the session closed meanwhile
                }
            }
            if (verb == "stream.stop" && ok && sid != null) {
                val o = owners[sid]
                if (o != null) {
                    release(sid)
                    h.put("dropped", h.optLong("dropped", 0) + o.dropped)
                }
            }
            done(if (rep.body != null) Reply.Binary(h, rep.body) else Reply.Json(h))
        }
    }

    private fun stopRemote(sid: String, spawn: Boolean) {
        val r = relay ?: return
        val c = if (spawn) JSONObject().put("cmd", "shell.kill").put("sid", sid).put("signal", "KILL")
        else JSONObject().put("cmd", "stream.stop").put("sid", sid)
        try { r.call(c, 5_000) {} } catch (_: Exception) {}
    }

    /** Removes [sid]'s owner exactly once and frees its slot. */
    private fun release(sid: String): StreamOwner? {
        val o = owners.remove(sid) ?: return null
        if (o.spawn) o.session.spawnSlots.decrementAndGet() else o.session.streamSlots.decrementAndGet()
        return o
    }

    // ------------------------------------------------------------------------------------------ streams

    private class StreamOwner(val session: Session, val droppable: Boolean, val spawn: Boolean) {
        val inFlight = ArrayDeque<Long>()          // seqs sent and not yet acked
        var pending: Pair<Long, ByteArray>? = null // the newest frame held back while the window is full
        var dropped = 0L
    }

    /** A frame from deskd with no `id`: route it to the connection that owns its `sid`. */
    private fun onStreamFrame(rep: DecodedReply) {
        val h = rep.header
        val sid = h.opt("sid")?.toString() ?: return
        val o = owners[sid] ?: return
        // Terminal frames: a process exit, or a stream that died ({sid, error}) / ended.
        if (rep.body == null && (h.has("exit") || h.has("error") || h.has("end") || h.optBoolean("stopped", false))) {
            release(sid)
            o.session.enqueue(Frame.encodeJson(h))
            return
        }
        val frame = if (rep.body != null) Frame.encodeBinary(h, rep.body) else Frame.encodeJson(h)
        if (!o.droppable) { o.session.enqueue(frame); return }
        val seq = h.optLong("seq", -1L)
        synchronized(o) {
            if (o.inFlight.size >= ACK_WINDOW) {
                if (o.pending != null) o.dropped++
                o.pending = seq to frame              // keep the newest, drop the older held frame
                return
            }
            o.inFlight.addLast(seq)
        }
        o.session.enqueue(frame)
    }

    private fun onAck(s: Session, cmd: JSONObject) {
        val sid = cmd.opt("sid")?.toString() ?: return
        val o = owners[sid] ?: return
        if (o.session !== s) return
        val seq = cmd.optLong("seq", Long.MIN_VALUE)
        var next: ByteArray? = null
        synchronized(o) {
            while (o.inFlight.isNotEmpty() && o.inFlight.first() <= seq) o.inFlight.removeFirst()
            val p = o.pending
            if (p != null && o.inFlight.size < ACK_WINDOW) {
                o.pending = null
                o.inFlight.addLast(p.first)
                next = p.second
            }
        }
        relay?.post(JSONObject().put("cmd", "stream.ack").put("sid", cmd.opt("sid")).put("seq", seq))
        next?.let { s.enqueue(it) }
    }

    // ------------------------------------------------------------------------------------------ session

    private inner class Session(val wire: Wire, val via: String, val genie: String, val generation: Long) {
        @Volatile var open = true
        @Volatile var lastCommandAt = 0L
        val inflight = AtomicInteger()
        val streamSlots = AtomicInteger()
        val spawnSlots = AtomicInteger()
        private val out = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicLong()
        private val closing = AtomicBoolean(false)
        private val poison = ByteArray(0)

        // No-id requests are answered in order (spec 01 §1.1): each gets a ticket; replies park until their turn.
        private var nextTicket = 0L
        private var nextSend = 0L
        private val parked = HashMap<Long, ByteArray>()

        private val writer = Thread({
            try {
                while (true) {
                    val f = out.take()
                    if (f === poison) break
                    queued.addAndGet(-f.size.toLong())
                    wire.write(f)
                }
            } catch (_: Exception) {
            }
            close("write ended")
        }, "genie-out").apply { isDaemon = true; start() }

        fun ticket(): Long = synchronized(parked) { nextTicket++ }

        fun deliver(ticket: Long, frame: ByteArray) {
            synchronized(parked) {
                parked[ticket] = frame
                while (true) {
                    val f = parked.remove(nextSend) ?: break
                    nextSend++
                    enqueue(f)
                }
            }
        }

        fun enqueue(frame: ByteArray) {
            if (!open) return
            if (queued.addAndGet(frame.size.toLong()) > MAX_QUEUED_BYTES) {
                env.log("genie connection too slow; closing")
                close("slow consumer", graceful = false)
                return
            }
            out.offer(frame)
        }

        /**
         * Lets already-queued frames (e.g. a final `unpaired` / `too_big` error) go out for up to 1 s, then
         * closes the wire, stops this connection's streams and spawned processes, and drops the session.
         */
        fun close(reason: String, graceful: Boolean = true) {
            if (!closing.compareAndSet(false, true)) return
            open = false
            out.offer(poison)
            if (graceful && Thread.currentThread() !== writer) {
                try { writer.join(1000) } catch (_: InterruptedException) {}
            }
            safeClose(wire)
            for ((sid, o) in owners.entries.toList()) {
                if (o.session === this && release(sid) != null) stopRemote(sid, o.spawn)
            }
            sessions.remove(this)
            env.log("genie disconnected ($via): $reason")
            try { env.onDisconnected(sessions.size) } catch (_: Exception) {}
        }
    }
}
