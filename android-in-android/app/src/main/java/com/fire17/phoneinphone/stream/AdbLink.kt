package com.fire17.phoneinphone.stream

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import dadb.adbserver.AdbServer
import java.io.File

/**
 * How the app reaches the guest's adbd.
 *
 *  DIRECT     — the app speaks the ADB wire protocol itself (dadb) to `host:5555`.
 *               Works for redroid (`-p 5555:5555`), Android emulators (odd console port +1, e.g.
 *               5555), and a spare phone after `adb tcpip 5555`. The guest shows an
 *               "Allow USB debugging?" RSA prompt the first time — except redroid, which by
 *               default has `ro.adb.secure=0`. (UNVERIFIED per image; accept it if prompted.)
 *
 *  ADB_SERVER — the app talks to an `adb` SERVER (port 5037) running on another machine, e.g.
 *               the Mac: `adb -a -P 5037 nodaemon server`. Useful for the Mac emulator, whose
 *               adbd is only bound to 127.0.0.1 on the Mac. `serial` picks the device
 *               (e.g. "emulator-5554"); blank = the only one.
 *
 * Android 11+ wireless-debugging PAIRING (TLS + SPAKE2) is NOT implemented: dadb does not
 * document it. Use `adb tcpip 5555` once over USB on the spare phone, or the ADB_SERVER mode.
 */
enum class LinkMode { DIRECT, ADB_SERVER }

data class LinkTarget(
    val mode: LinkMode,
    val host: String,
    val port: Int,
    val serial: String = "",
)

object AdbLink {

    /** Persistent per-install RSA key under filesDir/adb (never user.home, which is meaningless on Android). */
    fun keyPair(context: Context): AdbKeyPair {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    fun connect(context: Context, target: LinkTarget): Dadb = when (target.mode) {
        LinkMode.DIRECT -> Dadb.create(
            target.host,
            target.port,
            keyPair(context),
            connectTimeout = 8_000,
            socketTimeout = 0,
            keepAlive = true,
        )
        LinkMode.ADB_SERVER -> AdbServer.createDadb(
            adbServerHost = target.host,
            adbServerPort = target.port,
            deviceQuery = if (target.serial.isBlank()) "host:transport-any"
            else "host:transport:${target.serial}",
            connectTimeout = 8_000,
            socketTimeout = 0,
        )
    }

    /**
     * Opens `localabstract:<name>` on the guest, retrying while the freshly launched server is
     * still binding its socket. In tunnel_forward mode the first stream gets one dummy byte (0x00)
     * from the server: we consume and verify it here (doc/develop.md "Dummy byte").
     */
    fun openAbstract(dadb: Dadb, name: String, expectDummyByte: Boolean, timeoutMs: Long = 10_000): AdbStream {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val s = dadb.open("localabstract:$name")
                if (expectDummyByte) {
                    // If nothing listens yet, adbd closes the stream and readByte throws EOF.
                    try {
                        val b = s.source.readByte()
                        if (b.toInt() != 0) throw IllegalStateException("unexpected dummy byte $b")
                    } catch (t: Throwable) {
                        try { s.close() } catch (ignored: Throwable) {}
                        throw t
                    }
                }
                return s
            } catch (t: Throwable) {
                last = t
                Thread.sleep(150)
            }
        }
        throw IllegalStateException("could not open localabstract:$name in ${timeoutMs}ms", last)
    }
}
