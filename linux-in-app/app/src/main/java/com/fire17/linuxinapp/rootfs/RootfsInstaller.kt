package com.fire17.linuxinapp.rootfs

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs the bundled Alpine minirootfs into filesDir/rootfs on first launch.
 *
 * Idempotent: a marker file (rootfs/.linuxinapp-installed) records which asset version was
 * extracted. Extraction goes to a temp dir and is renamed into place atomically, so a crash
 * mid-extract never leaves a half-rootfs that looks "installed".
 */
class RootfsInstaller(private val context: Context) {

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val marker: File get() = File(rootfsDir, ".linuxinapp-installed")

    /** Asset name matching the ABI proot will run as (first supported 64-bit ABI we ship). */
    fun assetForDevice(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("Unsupported ABI ${Build.SUPPORTED_ABIS.joinToString()} — this build ships arm64-v8a and x86_64 only")
        return if (abi == "arm64-v8a") "rootfs/alpine-aarch64.tar.gz" else "rootfs/alpine-x86_64.tar.gz"
    }

    private fun bundledVersion(): String =
        try { context.assets.open("rootfs/VERSION").bufferedReader().readText().trim() } catch (_: Exception) { "unknown" }

    // Marker is written last, inside the temp dir, before the atomic rename — so its presence
    // with the right version means a complete tree. (Don't probe bin/busybox with File.exists():
    // if a future Alpine ships bin as an ABSOLUTE symlink it would resolve against the host.)
    fun isInstalled(): Boolean =
        marker.isFile && marker.readText().trim() == bundledVersion() && File(rootfsDir, "etc").isDirectory

    /** Blocking; call off the main thread. Returns a human-readable log line. */
    fun ensureInstalled(onProgress: (String) -> Unit = {}): String {
        if (isInstalled()) return "rootfs already installed (${bundledVersion()})"
        val asset = assetForDevice()
        val tmp = File(context.filesDir, "rootfs.partial")
        TarGzExtractor.deleteTreeNoFollow(tmp)
        tmp.mkdirs()
        onProgress("Extracting $asset …")
        val t0 = System.currentTimeMillis()
        val stats = context.assets.open(asset).use { TarGzExtractor(tmp).extract(it, onProgress) }
        writeGuestConfig(tmp)
        File(tmp, ".linuxinapp-installed").writeText(bundledVersion() + "\n")

        val old = File(context.filesDir, "rootfs.old")
        TarGzExtractor.deleteTreeNoFollow(old)
        if (rootfsDir.exists() && !rootfsDir.renameTo(old)) TarGzExtractor.deleteTreeNoFollow(rootfsDir)
        if (!tmp.renameTo(rootfsDir)) error("rename ${tmp.path} -> ${rootfsDir.path} failed")
        TarGzExtractor.deleteTreeNoFollow(old)

        val msg = "rootfs installed in ${System.currentTimeMillis() - t0} ms: $stats"
        Log.i(TAG, msg)
        return msg
    }

    /** DNS, hosts and a friendly profile — the minirootfs ships none of these usable on Android. */
    private fun writeGuestConfig(root: File) {
        val etc = File(root, "etc").apply { mkdirs() }
        // Android has no /etc/resolv.conf; the guest needs one for apk/wget.
        File(etc, "resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "hostname").writeText("linuxinapp\n")
        File(etc, "profile.d").mkdirs()
        File(etc, "profile.d/00-linuxinapp.sh").writeText(
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export PS1='\u@linuxinapp:\w\$ '
            [ -f /root/.motd-shown ] || { echo "Welcome to LinuxInApp — Alpine under PRoot. Try: apk update && apk add python3"; touch /root/.motd-shown; }
            """.trimIndent() + "\n"
        )
        File(root, "root").mkdirs()
        File(root, "tmp").mkdirs()
    }

    companion object { private const val TAG = "RootfsInstaller" }
}
