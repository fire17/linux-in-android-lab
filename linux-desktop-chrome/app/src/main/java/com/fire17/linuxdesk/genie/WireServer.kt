package com.fire17.linuxdesk.genie

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase A transport (spec 01 §2): `linuxdesk/1` over TCP on **127.0.0.1:8766 only** (never 0.0.0.0), into
 * the same [Router] as IrohLink. Test path: `adb forward tcp:8766 tcp:8766`, then
 * `genie-client pair --tcp 127.0.0.1:8766 --code ABC234`.
 *
 * ON only while "Developer: local link" is ON (GenieSettings.localLink, default OFF); GenieRuntime decides.
 * Any app on the phone can reach loopback, so the Router demands Binding auth on the first frame of every
 * connection (spec 01 §4 rule 3).
 */
class WireServer(
    private val router: Router,
    private val port: Int = 8766,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = CopyOnWriteArrayList<Socket>()
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "genie-wire-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (c in clients) try { c.close() } catch (_: Exception) {}
        clients.clear()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
                }
            } catch (t: Throwable) {
                // Port taken: report it, never fall back to another address (spec 01 §2).
                log("local link: port $port busy (${t.javaClass.simpleName}); retrying")
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
                continue
            }
            server = ss
            log("local link listening on 127.0.0.1:$port")
            try {
                while (running) {
                    val s = ss.accept()
                    if (!s.inetAddress.isLoopbackAddress) { try { s.close() } catch (_: Exception) {}; continue }
                    s.tcpNoDelay = true
                    clients += s
                    Thread({
                        try { router.serve(SocketWire(s), "tcp") } catch (_: Throwable) {}
                        clients.remove(s)
                        try { s.close() } catch (_: Exception) {}
                    }, "genie-wire-conn").apply { isDaemon = true; start() }
                }
            } catch (_: Throwable) {
                // closed by stop(), or a transient accept failure
            }
            try { ss.close() } catch (_: Exception) {}
            server = null
            if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    /** [Wire] over a blocking socket. */
    private class SocketWire(private val s: Socket) : Wire {
        private val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        private val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        override fun read(): ByteArray = Frame.readPayload(input, Frame.MAX_IN)
        override fun write(frame: ByteArray) {
            synchronized(output) {
                output.write(frame)
                output.flush()
            }
        }
        override fun close() { try { s.close() } catch (_: Exception) {} }
    }
}
