package com.fire17.linuxdesk.genie

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RouterTest {

    // ------------------------------------------------------------------ fakes

    /** The genie side of a connection: [send] pushes requests in, [next] pops decoded replies out. */
    private class FakeWire : Wire {
        val inbox = LinkedBlockingQueue<ByteArray>()
        val outbox = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        private val eof = ByteArray(0)

        fun send(o: JSONObject) { inbox.put(Frame.encodeRequest(o)) }
        fun sendRaw(payload: ByteArray) { inbox.put(payload) }
        fun hangUp() { inbox.put(eof) }
        fun next(ms: Long = 3000): DecodedReply? {
            val f = outbox.poll(ms, TimeUnit.MILLISECONDS) ?: return null
            return Frame.decodeReply(f.copyOfRange(4, f.size))
        }

        override fun read(): ByteArray {
            val f = inbox.take()
            if (f === eof || closed) throw EOFException()
            if (f.size >= 4 && Frame.u32(f) == (f.size - 4).toLong()) return f.copyOfRange(4, f.size)
            return f                                   // sendRaw: already a payload
        }
        override fun write(frame: ByteArray) { if (closed) throw java.io.IOException("closed"); outbox.put(frame) }
        override fun close() { closed = true; inbox.offer(eof) }
    }

    private class TooBigWire(private val inner: FakeWire) : Wire by inner {
        @Volatile var armed = false
        override fun read(): ByteArray {
            val p = inner.read()             // the reader may already be blocked here when the test arms it
            if (armed) { armed = false; throw FrameTooBig(Frame.MAX_IN + 1L) }
            return p
        }
    }

    private class FakeRelay : Relay {
        @Volatile var up = true
        @Volatile var upCaps = listOf("shell", "input", "screen", "browser", "stream")
        val calls = CopyOnWriteArrayList<JSONObject>()
        val posts = CopyOnWriteArrayList<JSONObject>()
        @Volatile var handler: (JSONObject, (DecodedReply) -> Unit) -> Unit = { c, cb ->
            cb(DecodedReply(JSONObject().put("ok", true).put("echo", c.optString("cmd")), null))
        }
        @Volatile var listener: ((DecodedReply) -> Unit)? = null

        override fun ready() = up
        override fun caps() = upCaps
        override fun call(cmd: JSONObject, timeoutMs: Long, onReply: (DecodedReply) -> Unit) {
            calls += cmd
            handler(cmd, onReply)
        }
        override fun post(cmd: JSONObject) { posts += cmd }
        override fun setStreamListener(l: ((DecodedReply) -> Unit)?) { listener = l }

        fun push(h: JSONObject, body: ByteArray? = null) = listener!!.invoke(DecodedReply(h, body))
    }

    private class FakeEnv : RouterEnv {
        @Volatile var bound = false
        @Volatile var token: String? = null
        @Volatile var gen = 1L
        @Volatile var window = true
        @Volatile var locked = false
        @Volatile var xor = false
        @Volatile var wide = false
        @Volatile var appClosedCalls = 0
        val fsCalls = CopyOnWriteArrayList<JSONObject>()
        val logs = CopyOnWriteArrayList<String>()
        @Volatile var fsReply: (JSONObject) -> Reply = { Reply.Json(JSONObject().put("ok", true).put("fs", it.optString("cmd"))) }

        override fun authenticate(given: String, genie: String): HelloResult {
            if (!bound) {
                if (given == "ABC234") { bound = true; token = "T".repeat(43); gen++; return HelloResult.Ok(genie, token) }
                return HelloResult.Refused("auth")
            }
            return if (given == token) HelloResult.Ok(genie, null) else HelloResult.Refused("bound")
        }
        override fun isBound() = bound
        override fun generation() = gen
        override fun windowOpen() = window
        override fun lockedRefusal() = locked
        override fun xorBlocks() = xor
        override fun wideWindowGate() = wide
        override fun fsInfo(): JSONObject = JSONObject().put("fs_mode", "scoped")
        override fun hwInfo(): JSONObject = JSONObject().put("soc", JSONObject().put("model", "SM8650"))
        override fun fs(cmd: JSONObject): Reply { fsCalls += cmd; return fsReply(cmd) }
        override fun version() = "0.1.0-test"
        override fun onAppClosed() { appClosedCalls++ }
        override fun log(line: String) { logs += line }
    }

    private lateinit var env: FakeEnv
    private lateinit var relay: FakeRelay
    private lateinit var auditFile: File
    private lateinit var router: Router

    @Before fun setUp() {
        env = FakeEnv()
        relay = FakeRelay()
        auditFile = File(Files.createTempDirectory("ld-audit").toFile(), "genie-audit.jsonl")
        router = Router(env, relay, Audit(auditFile))
    }

    @After fun tearDown() { router.shutdown() }

    private fun connect(w: Wire = FakeWire()): Wire {
        Thread({ router.serve(w, "tcp") }, "test-serve").apply { isDaemon = true; start() }
        return w
    }

    /** Pairs with the code, then returns a fresh connection authed with the token. */
    private fun authed(): FakeWire {
        if (!env.bound) {
            val p = connect() as FakeWire
            p.send(JSONObject().put("auth", "ABC234").put("genie", "g1").put("proto", "linuxdesk/1"))
            assertNotNull(p.next()!!.header.optString("token"))
            p.hangUp()
        }
        val w = connect() as FakeWire
        w.send(JSONObject().put("auth", env.token).put("genie", "g1"))
        val hello = w.next()!!.header
        assertTrue(hello.toString(), hello.optBoolean("ok"))
        return w
    }

    private fun cmd(w: FakeWire, verb: String, id: Int? = null, args: JSONObject = JSONObject()): JSONObject {
        val o = JSONObject(args.toString()).put("cmd", verb)
        if (id != null) o.put("id", id)
        w.send(o)
        return w.next()!!.header
    }

    // ------------------------------------------------------------------ hello / auth

    @Test fun pairingTradesCodeForToken() {
        val w = connect() as FakeWire
        w.send(JSONObject().put("auth", "ABC234").put("genie", "mac-genie").put("proto", "linuxdesk/1"))
        val r = w.next()!!.header
        assertTrue(r.getBoolean("ok"))
        assertTrue(r.getBoolean("paired"))
        assertEquals(43, r.getString("token").length)
        assertEquals("linuxdesk", r.getString("app"))
        assertEquals("linuxdesk/1", r.getString("proto"))
        val caps = r.getJSONArray("caps").toString()
        assertTrue(caps, caps.contains("sys") && caps.contains("fs") && caps.contains("browser"))
    }

    @Test fun wrongCodeGetsOneAuthErrorAndClose() {
        val w = connect() as FakeWire
        w.send(JSONObject().put("auth", "WRONG1").put("genie", "x"))
        assertEquals("auth", w.next()!!.header.getString("error"))
        Thread.sleep(500)
        assertTrue(w.closed)
        assertNull(w.next(200))
    }

    @Test fun unauthenticatedCommandIsRefused() {
        val w = connect() as FakeWire
        w.send(JSONObject().put("cmd", "shell.exec").put("sh", "id"))
        assertEquals("auth", w.next()!!.header.getString("error"))
        assertTrue(relay.calls.isEmpty())
    }

    @Test fun garbageFirstFrameIsAuthError() {
        val w = connect() as FakeWire
        w.sendRaw("hello".toByteArray())
        assertEquals("auth", w.next()!!.header.getString("error"))
    }

    @Test fun boundRefusesOthers() {
        authed()
        val w = connect() as FakeWire
        w.send(JSONObject().put("auth", "ABC234").put("genie", "intruder"))
        assertEquals("bound", w.next()!!.header.getString("error"))
    }

    @Test fun wrongProtoIsUnsupported() {
        val w = connect() as FakeWire
        w.send(JSONObject().put("auth", "ABC234").put("genie", "x").put("proto", "storagegenie/1"))
        assertEquals("unsupported", w.next()!!.header.getString("error"))
        assertFalse(env.bound)
    }

    @Test fun helloEchoesId() {
        val w = connect() as FakeWire
        w.send(JSONObject().put("id", 1).put("auth", "ABC234").put("genie", "x"))
        assertEquals(1, w.next()!!.header.getInt("id"))
    }

    @Test fun authValuesNeverLogged() {
        val w = authed()
        cmd(w, "sys.ping", 1)
        for (l in env.logs) {
            assertFalse(l, l.contains("ABC234"))
            assertFalse(l, l.contains(env.token!!))
        }
    }

    // ------------------------------------------------------------------ dispatch

    @Test fun pingReportsCapsAndEchoesId() {
        val w = authed()
        val r = cmd(w, "sys.ping", 42)
        assertEquals(42, r.getInt("id"))
        assertTrue(r.getBoolean("ok"))
        assertEquals("0.1.0-test", r.getString("version"))
        assertTrue(r.getJSONObject("session").getBoolean("deskd"))
        assertFalse(r.getBoolean("audio"))
    }

    @Test fun unknownCmd() {
        val w = authed()
        assertEquals("unknown_cmd", cmd(w, "nope.nope", 1).getString("error"))
        assertEquals("unknown_cmd", cmd(w, "update_offer", 2).getString("error"))   // no updater, ever
        assertEquals("unsupported", cmd(w, "android.su.exec", 3).getString("error"))
    }

    /** Tami: the mic turns on "only if I approve and turn it on" — no link verb may touch it, nothing relayed. */
    @Test fun micVerbsAreAlwaysDenied() {
        val w = authed()
        var id = 1
        for (v in listOf("mic.start", "mic.on", "mic.status", "microphone.enable", "audio.in.start", "audio.input",
                "audio.mic", "audio.record", "MIC.START")) {
            assertEquals(v, "denied", cmd(w, v, id++).getString("error"))
        }
        assertTrue(relay.calls.isEmpty())
        assertFalse(Router.isMicVerb("audio.out"))   // sound out is not the mic
    }

    @Test fun badJsonKeepsConnectionOpen() {
        val w = authed()
        w.sendRaw("{nope".toByteArray())
        assertEquals("bad_json", w.next()!!.header.getString("error"))
        assertTrue(cmd(w, "sys.ping", 2).getBoolean("ok"))
    }

    @Test fun nonIntegerIdIsBadArgs() {
        val w = authed()
        w.send(JSONObject().put("cmd", "sys.ping").put("id", "seven"))
        assertEquals("bad_args", w.next()!!.header.getString("error"))
    }

    @Test fun fsGoesToFsVerbsWithoutManualOrId() {
        val w = authed()
        val r = cmd(w, "fs.execute", 5, JSONObject().put("proposal", "p1").put("manual", true).put("nonce", "n"))
        assertEquals(5, r.getInt("id"))
        val sent = env.fsCalls.single()
        assertFalse("manual must never pass over the link", sent.has("manual"))
        assertFalse(sent.has("id"))
        assertEquals("p1", sent.getString("proposal"))
    }

    @Test fun fsBinaryReplyPassesThrough() {
        env.fsReply = { Reply.Binary(JSONObject().put("path", "a.txt").put("mime", "text/plain"), "hi".toByteArray()) }
        val w = authed()
        w.send(JSONObject().put("cmd", "fs.get").put("id", 8).put("path", "a.txt"))
        val r = w.next()!!
        assertTrue(r.isBinary)
        assertEquals(8, r.header.getInt("id"))
        assertEquals("hi", String(r.body!!))
    }

    @Test fun proposalOver200PathsRefused() {
        val w = authed()
        val paths = JSONArray().apply { repeat(201) { put("f$it") } }
        assertEquals("bad_args", cmd(w, "fs.propose_delete", 1, JSONObject().put("paths", paths)).getString("error"))
        assertTrue(env.fsCalls.isEmpty())
    }

    @Test fun linuxVerbRelayedWithoutCallerIdOrAuth() {
        val w = authed()
        val r = cmd(w, "shell.exec", 77, JSONObject().put("argv", JSONArray().put("id")).put("as", "root"))
        assertEquals(77, r.getInt("id"))
        assertEquals("shell.exec", r.getString("echo"))
        val sent = relay.calls.single()
        assertFalse(sent.has("id"))
        assertFalse(sent.has("auth"))
        assertEquals("root", sent.getString("as"))
    }

    @Test fun onlySpecVerbsAreRelayed() {
        val w = authed()
        assertEquals("unknown_cmd", cmd(w, "chrome.click", 1).getString("error"))
        assertEquals("unknown_cmd", cmd(w, "shell.sudo", 2).getString("error"))
        assertTrue(relay.calls.isEmpty())
    }

    @Test fun execTimeoutCapped() {
        val w = authed()
        assertEquals("bad_args", cmd(w, "shell.exec", 1, JSONObject().put("sh", "x").put("timeout_s", 601)).getString("error"))
        assertEquals("bad_args", cmd(w, "shell.exec", 2, JSONObject().put("sh", "x").put("timeout_s", 0)).getString("error"))
        assertTrue(cmd(w, "shell.exec", 3, JSONObject().put("sh", "x").put("timeout_s", 600)).getBoolean("ok"))
    }

    @Test fun deskdDownIsNotReady() {
        relay.up = false
        val w = authed()
        assertEquals("not_ready", cmd(w, "browser.tabs", 1).getString("error"))
        assertTrue(cmd(w, "sys.ping", 2).getBoolean("ok"))      // sys/fs still work
        assertTrue(cmd(w, "fs.list", 3).getBoolean("ok"))
    }

    @Test fun familyMissingFromCapsIsNotReady() {
        relay.upCaps = listOf("shell", "stream")
        val w = authed()
        assertEquals("not_ready", cmd(w, "browser.open", 1, JSONObject().put("url", "x")).getString("error"))
        val caps = cmd(w, "sys.caps", 2).getJSONArray("caps").toString()
        assertFalse(caps.contains("browser"))
    }

    // ------------------------------------------------------------------ gates (02 §03)

    @Test fun windowClosedDefaultGatesOnlyStorageGenieThree() {
        val w = authed()
        env.window = false
        assertEquals("app_closed", cmd(w, "fs.scan", 1).getString("error"))
        assertEquals("app_closed", cmd(w, "fs.propose_delete", 2, JSONObject().put("paths", JSONArray())).getString("error"))
        assertEquals("app_closed", cmd(w, "fs.execute", 3, JSONObject().put("proposal", "p")).getString("error"))
        assertEquals(3, env.appClosedCalls)
        // The Linux machine stays under the genie's control with the app off screen (02 §03, SG parity).
        assertTrue(cmd(w, "shell.exec", 4, JSONObject().put("sh", "x")).getBoolean("ok"))
        assertTrue(cmd(w, "input.click", 5).getBoolean("ok"))
        assertTrue(cmd(w, "browser.tabs", 6).getBoolean("ok"))
        assertTrue(cmd(w, "screen.capture", 7).getBoolean("ok"))
        assertTrue(cmd(w, "fs.list", 8).getBoolean("ok"))
        assertTrue(cmd(w, "fs.get", 9, JSONObject().put("path", "a")).getBoolean("ok"))
        assertTrue(cmd(w, "sys.ping", 10).getBoolean("ok"))
    }

    @Test fun wideWindowGateSettingGatesEveryActingVerb() {
        val w = authed()
        env.window = false
        env.wide = true
        assertEquals("app_closed", cmd(w, "fs.scan", 1).getString("error"))
        assertEquals("app_closed", cmd(w, "shell.exec", 2, JSONObject().put("sh", "x")).getString("error"))
        assertEquals("app_closed", cmd(w, "browser.tabs", 3).getString("error"))
        assertEquals("app_closed", cmd(w, "fs.list", 4).getString("error"))
        assertTrue(cmd(w, "sys.ping", 5).getBoolean("ok"))
        assertTrue(cmd(w, "fs.proposal", 6, JSONObject().put("proposal", "p")).getBoolean("ok"))
        assertTrue(cmd(w, "fs.log", 7).getBoolean("ok"))
        assertTrue(cmd(w, "shell.kill", 8, JSONObject().put("sid", "nope")).has("error"))   // not app_closed
        assertFalse(cmd(w, "shell.kill", 9, JSONObject().put("sid", "nope")).getString("error") == "app_closed")
    }

    @Test fun windowOpenNothingGatedEvenWide() {
        val w = authed()
        env.wide = true
        assertTrue(cmd(w, "fs.scan", 1).getBoolean("ok"))
        assertTrue(cmd(w, "shell.exec", 2, JSONObject().put("sh", "x")).getBoolean("ok"))
        assertEquals(0, env.appClosedCalls)
    }

    @Test fun capsAndPingCarryFsInfo() {
        val w = authed()
        assertEquals("scoped", cmd(w, "sys.caps", 1).getJSONObject("fs").getString("fs_mode"))
        assertEquals("scoped", cmd(w, "sys.ping", 2).getJSONObject("fs").getString("fs_mode"))
    }

    @Test fun capsCarriesHwInfo() {
        val w = authed()
        val caps = cmd(w, "sys.caps", 1)
        assertTrue(caps.getBoolean("ok"))
        assertEquals("SM8650", caps.getJSONObject("hw").getJSONObject("soc").getString("model"))
    }

    @Test fun lockedRefusesShellAndInputOnly() {
        val w = authed()
        env.locked = true
        assertEquals("denied", cmd(w, "shell.exec", 1, JSONObject().put("sh", "x")).getString("error"))
        assertEquals("denied", cmd(w, "input.click", 2).getString("error"))
        assertTrue(cmd(w, "browser.tabs", 3).getBoolean("ok"))
    }

    @Test fun xorGuardRefusesShellOnly() {
        val w = authed()
        env.xor = true
        assertEquals("denied", cmd(w, "shell.exec", 1, JSONObject().put("sh", "x")).getString("error"))
        assertTrue(cmd(w, "input.key", 2, JSONObject().put("keys", "Return")).getBoolean("ok"))
    }

    // ------------------------------------------------------------------ unpair

    @Test fun forgetEndsSessionOnNextCommand() {
        val w = authed()
        env.bound = false                                    // Forget my genie
        assertEquals("unpaired", cmd(w, "sys.ping", 9).getString("error"))
        Thread.sleep(1500)
        assertTrue(w.closed)
    }

    @Test fun generationChangeEndsSession() {
        val w = authed()
        env.gen++                                            // forget + re-pair elsewhere
        assertEquals("unpaired", cmd(w, "sys.ping", 1).getString("error"))
    }

    // ------------------------------------------------------------------ ordering / multiplexing

    @Test fun noIdRepliesStayInOrderEvenWhenSlowFirst() {
        relay.handler = { c, cb ->
            val slow = c.optString("sh") == "slow"
            Thread {
                if (slow) Thread.sleep(400)
                cb(DecodedReply(JSONObject().put("ok", true).put("which", c.optString("sh")), null))
            }.start()
        }
        val w = authed()
        w.send(JSONObject().put("cmd", "shell.exec").put("sh", "slow"))
        w.send(JSONObject().put("cmd", "shell.exec").put("sh", "fast"))
        assertEquals("slow", w.next()!!.header.getString("which"))
        assertEquals("fast", w.next()!!.header.getString("which"))
    }

    @Test fun idRepliesMayComeOutOfOrder() {
        relay.handler = { c, cb ->
            val slow = c.optString("sh") == "slow"
            Thread {
                if (slow) Thread.sleep(400)
                cb(DecodedReply(JSONObject().put("ok", true), null))
            }.start()
        }
        val w = authed()
        w.send(JSONObject().put("cmd", "shell.exec").put("sh", "slow").put("id", 1))
        w.send(JSONObject().put("cmd", "shell.exec").put("sh", "fast").put("id", 2))
        assertEquals(2, w.next()!!.header.getInt("id"))
        assertEquals(1, w.next()!!.header.getInt("id"))
    }

    @Test fun oversizeRequestGetsTooBigAndClose() {
        authed()
        val inner = FakeWire()
        val w = TooBigWire(inner)
        connect(w)
        inner.send(JSONObject().put("auth", env.token).put("genie", "g1"))
        assertTrue(inner.next()!!.header.optBoolean("ok"))
        w.armed = true
        inner.send(JSONObject().put("cmd", "sys.ping"))      // wakes read(), which throws FrameTooBig
        assertEquals("too_big", inner.next()!!.header.getString("error"))
        Thread.sleep(1500)
        assertTrue(inner.closed)
    }

    // ------------------------------------------------------------------ streams & limits

    private var nextSid = 0
    private fun streamRelay() {
        relay.handler = { c, cb ->
            when (c.optString("cmd")) {
                "stream.start", "shell.spawn" -> cb(DecodedReply(JSONObject().put("ok", true).put("sid", "s${++nextSid}"), null))
                "stream.stop" -> cb(DecodedReply(JSONObject().put("ok", true).put("frames", 10).put("dropped", 1), null))
                else -> cb(DecodedReply(JSONObject().put("ok", true), null))
            }
        }
    }

    @Test fun atMostTwoStreams() {
        streamRelay()
        val w = authed()
        assertTrue(cmd(w, "stream.start", 1, JSONObject().put("kind", "screen")).getBoolean("ok"))
        assertTrue(cmd(w, "stream.start", 2, JSONObject().put("kind", "screen")).getBoolean("ok"))
        assertEquals("busy", cmd(w, "stream.start", 3, JSONObject().put("kind", "screen")).getString("error"))
        assertTrue(cmd(w, "stream.stop", 4, JSONObject().put("sid", "s1")).getBoolean("ok"))
        assertTrue(cmd(w, "stream.start", 5, JSONObject().put("kind", "screen")).getBoolean("ok"))
    }

    @Test fun atMostFourSpawns() {
        streamRelay()
        val w = authed()
        repeat(4) { assertTrue(cmd(w, "shell.spawn", it, JSONObject().put("sh", "sleep 9")).getBoolean("ok")) }
        assertEquals("busy", cmd(w, "shell.spawn", 9, JSONObject().put("sh", "x")).getString("error"))
        relay.push(JSONObject().put("sid", "s1").put("exit", 0))
        assertEquals(0, w.next()!!.header.getInt("exit"))
        assertTrue(cmd(w, "shell.spawn", 10, JSONObject().put("sh", "x")).getBoolean("ok"))
    }

    @Test fun ackWindowKeepsNewestAndDropsOlder() {
        streamRelay()
        val w = authed()
        val sid = cmd(w, "stream.start", 1, JSONObject().put("kind", "screen")).getString("sid")
        for (seq in 1..8) relay.push(JSONObject().put("sid", sid).put("seq", seq).put("mime", "image/jpeg"), byteArrayOf(seq.toByte()))
        val got = (1..4).map { w.next()!!.header.getInt("seq") }
        assertEquals(listOf(1, 2, 3, 4), got)
        assertNull("window full: nothing more until an ack", w.next(300))
        w.send(JSONObject().put("cmd", "stream.ack").put("sid", sid).put("seq", 4))
        assertEquals("the newest held frame is sent", 8, w.next()!!.header.getInt("seq"))
        assertNull(w.next(300))
        assertTrue("ack forwarded to deskd", relay.posts.any { it.optString("cmd") == "stream.ack" })
        val stop = cmd(w, "stream.stop", 2, JSONObject().put("sid", sid))
        assertEquals(1 + 3, stop.getInt("dropped"))           // deskd's 1 + our 3 (seq 5,6,7)
    }

    @Test fun ackProducesNoReply() {
        streamRelay()
        val w = authed()
        val sid = cmd(w, "stream.start", 1).getString("sid")
        w.send(JSONObject().put("cmd", "stream.ack").put("sid", sid).put("seq", 0))
        assertNull(w.next(300))
    }

    @Test fun cannotTouchAnotherConnectionsStream() {
        streamRelay()
        val a = authed()
        val sid = cmd(a, "shell.spawn", 1, JSONObject().put("sh", "cat")).getString("sid")
        val b = connect() as FakeWire
        b.send(JSONObject().put("auth", env.token).put("genie", "g1"))
        assertTrue(b.next()!!.header.optBoolean("ok"))
        assertEquals("bad_args", cmd(b, "shell.kill", 1, JSONObject().put("sid", sid)).getString("error"))
        assertEquals("bad_args", cmd(b, "shell.write", 2, JSONObject().put("sid", sid).put("eof", true)).getString("error"))
    }

    @Test fun disconnectStopsOwnedStreams() {
        streamRelay()
        val w = authed()
        cmd(w, "stream.start", 1, JSONObject().put("kind", "screen"))
        cmd(w, "shell.spawn", 2, JSONObject().put("sh", "cat"))
        relay.calls.clear()
        w.hangUp()
        Thread.sleep(1500)
        val verbs = relay.calls.map { it.optString("cmd") }.toSet()
        assertTrue(verbs.toString(), "stream.stop" in verbs && "shell.kill" in verbs)
    }

    // ------------------------------------------------------------------ audit

    @Test fun auditRowsHaveNoArguments() {
        val w = authed()
        cmd(w, "input.type", 1, JSONObject().put("text", "my secret password"))
        cmd(w, "shell.exec", 2, JSONObject().put("sh", "echo hunter2").put("stdin_b64", "c2VjcmV0"))
        val r = cmd(w, "sys.audit", 3)
        val rows = r.getJSONArray("rows")
        assertTrue(rows.length() >= 2)
        val text = auditFile.readText()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("c2VjcmV0"))
        val row = rows.getJSONObject(0)
        assertEquals(setOf("t", "cmd", "ok", "ms", "via"), row.keys().asSequence().toSet())
    }

    @Test fun auditRecordsErrorsAndRotates() {
        val f = File(Files.createTempDirectory("ld-audit2").toFile(), "a.jsonl")
        val a = Audit(f, maxBytes = 400)
        repeat(20) { a.record(1000L + it, "shell.exec", it % 2 == 0, "denied", 5, "iroh") }
        assertTrue(f.length() <= 400)
        assertTrue(File(f.path + ".1").isFile)
        val q = a.query(0)
        assertTrue(q.getInt("total") > 0)
        val err = (0 until q.getJSONArray("rows").length()).map { q.getJSONArray("rows").getJSONObject(it) }
            .first { !it.getBoolean("ok") }
        assertEquals("denied", err.getString("error"))
        assertEquals(0, a.query(5_000).getInt("total"))
    }

    @Test fun auditSanitizesVerbNames() {
        val f = File(Files.createTempDirectory("ld-audit3").toFile(), "a.jsonl")
        Audit(f).record(1, "shell.exec\n{\"x\":1}", true, null, 1, "tcp")
        assertEquals("?", JSONObject(f.readLines().single()).getString("cmd"))
    }
    // ------------------------------------------------------------------ sys.logs

    private fun logsRouter(ring: LogRing): Router {
        router.shutdown()
        router = Router(env, relay, Audit(auditFile), logs = ring)
        return router
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun sysLogsTailDeltaAndCursorSurvivesDroppedLines() {
        val ring = LogRing(capacity = 10)
        logsRouter(ring)
        repeat(5) { ring.add("line $it") }
        val w = authed()

        val t = cmd(w, "sys.logs", 1, JSONObject().put("n", 3))
        assertTrue(t.toString(), t.getBoolean("ok"))
        assertEquals(listOf("line 2", "line 3", "line 4"), strings(t.getJSONArray("lines")))
        assertEquals(5, t.getInt("cursor"))
        assertEquals(0, t.getInt("dropped"))
        assertEquals(5, strings(cmd(w, "sys.logs", 2).getJSONArray("lines")).size)   // default tail 200

        ring.add("line 5"); ring.add("line 6")
        val d = cmd(w, "sys.logs", 3, JSONObject().put("mode", "delta").put("since", 5).put("epoch", t.getLong("epoch")))
        assertEquals(listOf("line 5", "line 6"), strings(d.getJSONArray("lines")))
        assertEquals(7, d.getInt("cursor"))
        assertEquals(0, strings(cmd(w, "sys.logs", 4, JSONObject().put("mode", "delta").put("since", 7))
            .getJSONArray("lines")).size)                                              // nothing new

        repeat(20) { ring.add("more $it") }                                            // buffer holds 10
        val lost = cmd(w, "sys.logs", 5, JSONObject().put("mode", "delta").put("since", 7))
        assertEquals(27, lost.getInt("cursor"))
        assertEquals(10, lost.getInt("dropped"))                                       // seqs 7..16 fell out
        assertEquals("more 10", strings(lost.getJSONArray("lines")).first())

        val all = cmd(w, "sys.logs", 6, JSONObject().put("mode", "all"))
        assertEquals(10, all.getJSONArray("lines").length())
        val other = cmd(w, "sys.logs", 7, JSONObject().put("mode", "delta").put("since", 3).put("epoch", 1))
        assertTrue(other.getBoolean("reset"))                                          // cursor from an older process
        assertEquals("bad_args", cmd(w, "sys.logs", 8, JSONObject().put("mode", "follow")).getString("error"))
        assertTrue(cmd(w, "sys.logs", 9).getJSONArray("audit").length() > 0)          // audit rows ride along
    }

    @Test fun sysLogsIsUngatedEvenWithWideGateAndWindowClosed() {
        val ring = LogRing()
        logsRouter(ring)
        ring.add("hello")
        val w = authed()
        env.window = false
        env.wide = true
        val r = cmd(w, "sys.logs", 1)
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(listOf("hello"), strings(r.getJSONArray("lines")))
        assertEquals(0, env.appClosedCalls)
        assertFalse(Router.windowGated("sys.logs", wide = true))
        assertFalse(Router.windowGated("sys.logs", wide = false))
    }

    @Test fun sysLogsCutsOldestLinesFirstUnderBudget() {
        val ring = LogRing()
        repeat(100) { ring.add("x".repeat(90) + it) }
        val r = (SysLogs.answer(JSONObject().put("mode", "all"), ring, null, budget = 2000) as Reply.Json).obj
        val lines = strings(r.getJSONArray("lines"))
        assertTrue(lines.size in 1..20)
        assertEquals("x".repeat(90) + 99, lines.last())                               // newest kept
        assertEquals(100 - lines.size, r.getInt("truncated"))
        assertEquals(100, r.getInt("cursor"))
    }
}
