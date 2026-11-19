package com.fire17.linuxdesk.genie

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import computer.iroh.SendStream
import computer.iroh.presetN0
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Phase B transport (spec 01 §2): the phone's iroh endpoint, ALPN exactly `linuxdesk/1` — a port of Storage
 * Genie's Link.kt bind/accept loop. It never accepts `storagegenie/1`, `genie/1` or `droidhand/1`.
 *
 * `Endpoint.bind(presetN0(), secretKey = Binding.secretKey, alpns = [ALPN])` → accept → check the ALPN →
 * `acceptBi()`; then the bi-stream is handed to the same [Router] as the WireServer (hello → Binding).
 * If iroh cannot bind (no network), it reports offline and retries every 3 s, like Link.kt.
 *
 * The uniffi iroh API is `suspend` (checked against iroh-1.1.0-sources.jar: bind/acceptNext/accept/alpn/connect/
 * acceptBi/readExact/writeAll/finish/shutdown suspend; recv/send/close not). This app has no kotlinx-coroutines
 * on its compile classpath (iroh pulls it at runtime scope only), so [blocking] runs each suspend call with the
 * stdlib's `startCoroutine` under EmptyCoroutineContext + a latch. uniffi suspends via
 * `suspendCancellableCoroutine` and resumes from its Rust callback thread; with no dispatcher in the context
 * that resumes our latch directly. Each connection gets its own thread, as in WireServer.
 */
class IrohLink(
    private val secretKey: () -> SecretKey,
    private val router: Router,
    private val log: (String) -> Unit = {},
    private val onOnline: (Boolean) -> Unit = {},
) {
    companion object {
        val ALPN: ByteArray = Router.PROTO.toByteArray(Charsets.UTF_8)

        /** Runs a suspend block to completion on this thread (stdlib only). Rethrows its failure. */
        fun <T> blocking(timeoutMs: Long = 0, block: suspend () -> T): T {
            val latch = CountDownLatch(1)
            var out: Result<T>? = null
            block.startCoroutine(object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) { out = result; latch.countDown() }
            })
            if (timeoutMs > 0) {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw java.util.concurrent.TimeoutException()
            } else {
                latch.await()
            }
            return out!!.getOrThrow()
        }
    }

    @Volatile private var running = false
    @Volatile private var endpoint: Endpoint? = null
    @Volatile var online = false
        private set
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "genie-iroh").apply { isDaemon = true; start() }
    }

    /** Stop genie: close the endpoint so the genie can no longer reach the phone. */
    @Synchronized
    fun stop() {
        running = false
        val ep = endpoint
        endpoint = null
        if (ep != null) {
            Thread({ shutdown(ep) }, "genie-iroh-close")
                .apply { isDaemon = true; start() }
        }
        thread?.interrupt()
        thread = null
        setOnline(false)
    }

    /** Graceful iroh shutdown (suspend), then free the native handle (AutoCloseable.close). */
    private fun shutdown(ep: Endpoint) {
        try { blocking(5_000) { ep.shutdown() } } catch (_: Throwable) {}
        try { ep.close() } catch (_: Throwable) {}
    }

    private fun setOnline(v: Boolean) {
        if (online == v) return
        online = v
        try { onOnline(v) } catch (_: Throwable) {}
    }

    private fun loop() {
        while (running) {
            try {
                val ep = blocking(30_000) {
                    Endpoint.bind(EndpointOptions(preset = presetN0(), secretKey = secretKey().toBytes(), alpns = listOf(ALPN)))
                }
                endpoint = ep
                setOnline(true)
                log("genie link online (iroh, ALPN ${Router.PROTO})")
                acceptLoop(ep)
                endpoint = null
                shutdown(ep)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (running) log("genie link offline: ${t.javaClass.simpleName}; retrying in 3 s")
            }
            setOnline(false)
            if (!running) break
            try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    private fun acceptLoop(ep: Endpoint) {
        while (running) {
            val inc = try { blocking { ep.acceptNext() } } catch (t: Throwable) {
                if (!running) return
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            if (inc == null) {
                // The endpoint was closed (acceptNext returns null when there will be no more connections).
                if (!running) return
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
                continue
            }
            Thread(conn@{
                try {
                    val accepting = blocking(30_000) { inc.accept() }
                    if (!blocking(10_000) { accepting.alpn() }.contentEquals(ALPN)) return@conn   // wrong ALPN: drop
                    val conn = blocking(30_000) { accepting.connect() }
                    val bi = blocking(30_000) { conn.acceptBi() }
                    val wire = IrohWire(bi.recv(), bi.send()) {
                        try { conn.close(0L, "bye".toByteArray()) } catch (_: Throwable) {}
                    }
                    router.serve(wire, "iroh")
                } catch (t: Throwable) {
                    log("genie session ended: ${t.javaClass.simpleName}")
                }
            }, "genie-iroh-conn").apply { isDaemon = true; start() }
        }
    }

    /** [Wire] over one iroh bi-stream. Writes are serialized; iroh's flow control is the backpressure. */
    private class IrohWire(
        private val recv: RecvStream,
        private val send: SendStream,
        private val onClose: () -> Unit,
    ) : Wire {
        private val writeLock = Any()
        @Volatile private var closed = false

        override fun read(): ByteArray {
            val h = blocking { recv.readExact(4u) }
            val n = Frame.u32(h)
            if (n > Frame.MAX_IN) throw FrameTooBig(n)
            return if (n == 0L) ByteArray(0) else blocking { recv.readExact(n.toUInt()) }
        }

        override fun write(frame: ByteArray) {
            if (closed) throw java.io.IOException("closed")
            synchronized(writeLock) { blocking { send.writeAll(frame) } }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { synchronized(writeLock) { blocking(2_000) { send.finish() } } } catch (_: Throwable) {}
            onClose()
        }
    }
}
