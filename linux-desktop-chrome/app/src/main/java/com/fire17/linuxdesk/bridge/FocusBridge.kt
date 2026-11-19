package com.fire17.linuxdesk.bridge

import android.util.Log
import com.fire17.linuxdesk.SessionBus
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * X11 gives the Android side no signal that a text field took focus, so Gboard never pops up by itself.
 * lorie's own comment: "there is no direct way to focus inside X windows". This bridge closes that gap.
 *
 *   web page focusin/focusout (content.js)
 *     -> extension service worker (background.js)
 *     -> GET http://127.0.0.1:<port>/kbd?t=<token>&show=1|0
 *     -> this server -> SessionBus.requestKeyboard() -> DesktopActivity -> LorieView.setKeyboardVisible()
 *
 * proot shares the app's network namespace, so 127.0.0.1 inside the guest is this app's loopback.
 * A random per-session token stops other apps on the phone from toggling the keyboard. Toggling the
 * keyboard is the only thing the endpoint can do.
 *
 * This design came from the research lane's proposal and is UNTESTED on a device. Known gap: Chromium's
 * own omnibox is browser UI, not web content, so tapping it does not trigger the bridge. Use the
 * keyboard button or Back for the omnibox.
 */
class FocusBridge {
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1

    fun start(): FocusBridge {
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "FocusBridge").apply { isDaemon = true; start() }
        Log.i(TAG, "listening on 127.0.0.1:${s.localPort}")
        return this
    }

    /** Contents of focus-bridge/config.js inside the guest. */
    fun configJs(): String = "self.LINUXDESK = { port: $port, token: \"$token\" };\n"

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            try { handle(c) } catch (e: Exception) { Log.w(TAG, "request failed", e) } finally { try { c.close() } catch (_: Exception) {} }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 2000
        val reader = c.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        // Read the remaining headers up to the blank line. If unread data is left, close() sends an RST.
        while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
        // "GET /kbd?t=...&show=1 HTTP/1.1". The rest of the request is ignored.
        val target = requestLine.split(' ').getOrNull(1) ?: ""
        val query = target.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val ok = target.startsWith("/kbd") && params["t"] == token
        if (ok) SessionBus.requestKeyboard(params["show"] == "1")
        val status = if (ok) "204 No Content" else "403 Forbidden"
        c.getOutputStream().write(
            ("HTTP/1.1 $status\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray()
        )
    }

    private companion object { const val TAG = "FocusBridge" }
}
