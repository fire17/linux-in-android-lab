package com.fire17.linuxdesk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * "Phone camera for Linux" (cameras v1+v2). The same consent rule as audio/MicIn:
 *  - [consent] lives in memory only, starts false on every process start, and nothing persists it.
 *  - Only the in-app switch (LauncherActivity, after CAMERA is granted) sets it true. The genie Router refuses
 *    camera.* / cam.* outright, and no intent extra carries an "on" value.
 *  - OFF (switch / notification / session end) stops the [CameraServer] at once: every camera closes, the port goes
 *    away, and state.json says enabled:false so pages get NotAllowedError without a Chromium restart.
 *
 * Guest side: the phone-camera extension (assets/linuxdesk/phone-camera), copied to rootfs/opt/linuxdesk/phone-camera.
 *  - `config.js` (content script, read once when Chromium loads the extension): bootstrap values.
 *  - `state.json` (read LIVE by the extension service worker with cache:'no-store'): enabled, port, token, cameras.
 *    Rewritten on every ON/OFF ([writeConfig]).
 * The extension is loaded next to focus-bridge only if the camera was ON when Chromium started
 * ([extensionDirsForLaunch]); after that, ON/OFF never needs a restart.
 *
 * Token and port are per SESSION: kept across OFF→ON (so already-open pages keep working), reset by [endSession].
 */
object PhoneCamera {
    /** Path inside the guest (copied from assets by RootfsInstaller.installHelpers). */
    const val EXTENSION_DIR = "/opt/linuxdesk/phone-camera"

    /** Extra per-page "Allow … to use the phone camera?" prompt in the extension. A recommendation, so OFF until
     *  Tami turns it on (the master switch is the consent today). */
    const val ASK_PER_PAGE = false

    @Volatile var consent = false
        private set

    @Volatile private var server: CameraServer? = null
    @Volatile private var sessionToken: String? = null
    @Volatile private var lastPort = 0

    /** True when the running Chromium was launched with the phone-camera extension. */
    @Volatile var extensionLoaded = false
        private set

    val running: Boolean get() = server != null

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** true: the in-app switch only. false: anyone (stopping is always allowed). */
    internal fun setConsent(on: Boolean) { consent = on; if (!on) stop() }

    /** Starts the loopback MJPEG server (cameras open only when a page reads a stream). Null: no permission/camera. */
    @Synchronized
    fun start(ctx: Context): CameraServer? {
        if (!consent || !hasPermission(ctx)) return null
        server?.let { return it }
        val tok = sessionToken ?: CameraServer.newToken().also { sessionToken = it }
        val s = CameraServer(ctx.applicationContext, tok, lastPort)
        s.start()
        if (s.cams.isEmpty()) { s.stop(); return null }
        lastPort = s.port
        server = s
        return s
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    /** Session over: forget token/port; the next session's Chromium starts without the extension unless ON. */
    @Synchronized
    fun endSession() {
        setConsent(false)
        sessionToken = null
        lastPort = 0
        extensionLoaded = false
    }

    /** For ChromiumFlags at (re)launch: the extension dir if the camera is ON now. Records what Chromium loaded. */
    fun extensionDirsForLaunch(): List<String> {
        extensionLoaded = running
        return if (extensionLoaded) listOf(EXTENSION_DIR) else emptyList()
    }

    /** config.js contents. Port 0 / enabled:false = off (main.js then adds no camera). Pure; testable. */
    fun configJs(port: Int, token: String, askPerPage: Boolean = ASK_PER_PAGE, enabled: Boolean = port > 0): String {
        val safe = token.filter { it.isLetterOrDigit() }
        return "// Written by LinuxDesk. Port 0 / enabled:false = phone camera off. Live state: state.json.\n" +
            "var __LINUXDESK_CAM__ = { enabled: $enabled, port: $port, token: \"$safe\", ask: $askPerPage };\n"
    }

    /** state.json contents ([camsJson] = CameraServer /cams array). Pure; testable. */
    fun stateJson(enabled: Boolean, port: Int, token: String, camsJson: String, askPerPage: Boolean = ASK_PER_PAGE): String {
        val safe = token.filter { it.isLetterOrDigit() }
        val on = enabled && port > 0
        return "{\"enabled\":$on,\"port\":${if (on) port else 0},\"token\":\"${if (on) safe else ""}\"," +
            "\"ask\":$askPerPage,\"cams\":${if (on) camsJson else "[]"}}\n"
    }

    /** Writes rootfs/opt/linuxdesk/phone-camera/{config.js,state.json} for the current server state. */
    fun writeConfig(rootfsDir: File) {
        val dir = File(rootfsDir, "opt/linuxdesk/phone-camera")
        if (!dir.isDirectory) return
        val s = server
        val cams = s?.cams?.joinToString(",", "[", "]") { it.json() } ?: "[]"
        File(dir, "config.js").writeText(if (s != null) configJs(s.port, s.token) else configJs(0, ""))
        // Write-then-rename so the service worker never reads half a file.
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(if (s != null) stateJson(true, s.port, s.token, cams) else stateJson(false, 0, "", "[]"))
        if (!tmp.renameTo(File(dir, "state.json"))) File(dir, "state.json").writeText(tmp.readText())
    }
}
