package com.fire17.linuxdesk.genie

import java.io.File

/**
 * Finds deskd processes left over from an earlier session (0.2.29). Seen on Tami's phone 2026-11-20: an Alpine
 * session's deskd outlived Stop, the Omarchy session's deskd then looped "port 8765 busy; no pid file" forever,
 * because deskd's pidfile lives in /run/linuxdesk INSIDE each rootfs and the other distro cannot see it.
 *
 * Android mounts /proc with hidepid, so only the app's own uid is visible: every match is ours. A match is any
 * process whose argv contains [MARK] (the guest python3 AND its proot wrapper, whose argv ends with deskd.py).
 * Pure JVM, so StaleDeskdTest runs on the plain JVM; the caller kills the returned PIDs.
 */
object StaleDeskd {
    const val MARK = "/opt/linuxdesk/deskd/deskd.py"

    /** argv of /proc/<pid>/cmdline (NUL-separated); empty when unreadable. */
    fun argv(raw: ByteArray): List<String> =
        String(raw, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }

    fun isDeskd(argv: List<String>): Boolean = argv.any { it == MARK }

    /** PIDs of deskd processes (guest python3 and proot wrappers), minus [except]. */
    fun find(procDir: File = File("/proc"), except: Set<Int> = emptySet()): List<Int> {
        val out = ArrayList<Int>()
        procDir.listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid in except) return@forEach
            val a = try { argv(File(d, "cmdline").readBytes()) } catch (_: Exception) { return@forEach }
            if (isDeskd(a)) out += pid
        }
        return out.sorted()
    }
}
