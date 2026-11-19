package com.fire17.linuxdesk.x11

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.system.Os
import android.util.Log
import com.termux.x11.LinuxDeskX11Entry
import java.io.File

/**
 * Hosts the embedded lorie X server (libXlorie.so) in the dedicated ":x11" process (see AndroidManifest).
 *
 * Only SessionService binds it, with BIND_AUTO_CREATE | BIND_IMPORTANT. The first bind starts the server.
 * Unbinding destroys the service, and we then kill the whole process: lorie's native threads cannot be
 * shut down any other way, and the next session needs a fresh server and socket. DroidDesk does the same.
 *
 * Paths: TMPDIR = <filesDir>/rootfs/tmp, so the X socket is <rootfs>/tmp/.X11-unix/X0. The proot guest sees
 * that path as /tmp/.X11-unix/X0, which is DISPLAY=:0 with no extra bind. lorie's native start() derives
 * XKB_CONFIG_ROOT from dirname(TMPDIR)/usr/share/X11/xkb, which is the Alpine package xkeyboard-config.
 * We also set it explicitly. lorie compiles xkbcomp in, so no xkbcomp binary is exec'd.
 */
class X11ServerService : Service() {

    companion object {
        const val EXTRA_DPI = "dpi"
        const val EXTRA_LEGACY_DRAWING = "legacyDrawing"
        /** Directory name under filesDir of the session's rootfs (Distro.dirName). Missing = "rootfs" (Alpine). */
        const val EXTRA_ROOTFS = "rootfsDir"
        private const val TAG = "LinuxDeskX11"
    }

    private val binder = Binder()

    override fun onBind(intent: Intent): IBinder {
        if (!LinuxDeskX11Entry.isStarted()) {
            try {
                startServer(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "X server failed to start", t)
                // Kill the process so SessionService gets onServiceDisconnected and reports the error.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        return binder
    }

    private fun startServer(intent: Intent) {
        val dirName = intent.getStringExtra(EXTRA_ROOTFS)?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: "rootfs"
        val rootfs = File(filesDir, dirName)
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        // A previous :x11 process that was killed leaves its socket and lock file behind.
        File(tmp, ".X11-unix/X0").delete()
        File(tmp, ".X0-lock").delete()

        Os.setenv("TMPDIR", tmp.absolutePath, true)
        Os.setenv("HOME", filesDir.absolutePath, true)
        // Arch ships usr/share/X11/xkb as an ABSOLUTE symlink to /usr/share/xkeyboard-config-2, which resolves
        // against the host here, so prefer the real directory when it exists. Alpine has no such dir: unchanged.
        val xkb = File(rootfs, "usr/share/xkeyboard-config-2").takeIf { it.isDirectory } ?: File(rootfs, "usr/share/X11/xkb")
        if (xkb.isDirectory) {
            Os.setenv("XKB_CONFIG_ROOT", xkb.absolutePath, true)
        } else {
            Log.w(TAG, "XKB data missing at $xkb (is xkeyboard-config installed?); lorie will refuse to start")
        }

        val args = mutableListOf(":0", "-nolock")
        val dpi = intent.getIntExtra(EXTRA_DPI, 0)
        if (dpi in 72..1000) args += listOf("-dpi", dpi.toString())
        // Upstream's documented fix for devices that show only a black screen with a cursor.
        if (intent.getBooleanExtra(EXTRA_LEGACY_DRAWING, false)) args += "-legacy-drawing"

        Log.i(TAG, "TMPDIR=${tmp.absolutePath} args=$args")
        LinuxDeskX11Entry.start(applicationContext, args.toTypedArray())
    }

    override fun onDestroy() {
        Log.i(TAG, "X server service destroyed; terminating the :x11 process")
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
