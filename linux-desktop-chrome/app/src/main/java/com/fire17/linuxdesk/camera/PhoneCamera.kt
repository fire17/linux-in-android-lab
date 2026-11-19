package com.fire17.linuxdesk.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * "Phone camera for Linux" (cameras v1). The same consent rule as audio/MicIn:
 *  - [consent] lives in memory only, starts false on every process start, and nothing persists it.
 *  - Only the in-app switch (LauncherActivity, after CAMERA is granted) sets it true. The genie Router refuses
 *    camera.* / cam.* outright, and no intent extra carries an "on" value.
 *  - OFF (switch / session end) stops the [CameraServer] at once: the camera closes and the port goes away.
 *
 * Guest side: the phone-camera extension (assets/linuxdesk/phone-camera) reads `config.js`, which [writeConfig]
 * rewrites under rootfs/opt/linuxdesk/phone-camera at session start (port + per-session token). It is loaded
 * next to focus-bridge only while [consent] is on when Chromium starts (see [EXTENSION_DIR]).
 */
object PhoneCamera {
    /** Path inside the guest (copied from assets by RootfsInstaller.installHelpers). */
    const val EXTENSION_DIR = "/opt/linuxdesk/phone-camera"

    @Volatile var consent = false
        private set

    @Volatile private var server: CameraServer? = null

    val running: Boolean get() = server != null

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** true: the in-app switch only. false: anyone (stopping is always allowed). */
    internal fun setConsent(on: Boolean) { consent = on; if (!on) stop() }

    /** Starts the loopback MJPEG server (the camera itself opens only when a page reads the stream). */
    @Synchronized
    fun start(ctx: Context): CameraServer? {
        if (!consent || !hasPermission(ctx)) return null
        server?.let { return it }
        val s = CameraServer(ctx.applicationContext)
        if (s.backCameraId() == null) return null
        s.start()
        server = s
        return s
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    /** config.js contents for the guest extension. Port 0 = off (main.js then does nothing). Pure; testable. */
    fun configJs(port: Int, token: String, askPerPage: Boolean = ASK_PER_PAGE): String {
        val safe = token.filter { it.isLetterOrDigit() }
        return "// Written by LinuxDesk at session start. Port 0 = phone camera off.\n" +
            "var __LINUXDESK_CAM__ = { port: $port, token: \"$safe\", ask: $askPerPage };\n"
    }

    /** Extra per-page "Allow … to use the phone camera?" prompt in the extension. A recommendation, so OFF until
     *  Tami turns it on (the master switch is the consent today). */
    const val ASK_PER_PAGE = false

    /** Writes rootfs/opt/linuxdesk/phone-camera/config.js for the current server state (off when not running). */
    fun writeConfig(rootfsDir: File) {
        val dir = File(rootfsDir, "opt/linuxdesk/phone-camera")
        if (!dir.isDirectory) return
        val s = server
        File(dir, "config.js").writeText(if (s != null) configJs(s.port, s.token) else configJs(0, ""))
    }
}
