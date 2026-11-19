package com.fire17.linuxdesk.linux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs the bundled Alpine minirootfs into filesDir/rootfs on first launch, then (separately) the
 * desktop packages (Chromium, Hebrew fonts, X helpers) with `apk` inside proot.
 * Adapted from linux-in-app's RootfsInstaller. One change: trees are deleted with
 * TarGzExtractor.deleteTreeNoFollow, never with File.deleteRecursively. The latter follows the guest's
 * absolute symlinks into the HOST filesystem.
 */
class RootfsInstaller(private val context: Context) {

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val marker: File get() = File(rootfsDir, ".linuxdesk-installed")

    fun assetForDevice(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("Unsupported ABI ${Build.SUPPORTED_ABIS.joinToString()}; this build ships arm64-v8a and x86_64 only")
        return if (abi == "arm64-v8a") "rootfs/alpine-aarch64.rootfs.bin" else "rootfs/alpine-x86_64.rootfs.bin"
    }

    private fun bundledVersion(): String =
        try { context.assets.open("rootfs/VERSION").bufferedReader().readText().trim() } catch (_: Exception) { "unknown" }

    fun isInstalled(): Boolean =
        marker.isFile && marker.readText().trim() == bundledVersion() && File(rootfsDir, "etc").isDirectory

    /** Blocking; call off the main thread. */
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
        File(tmp, ".linuxdesk-installed").writeText(bundledVersion() + "\n")

        val old = File(context.filesDir, "rootfs.old")
        TarGzExtractor.deleteTreeNoFollow(old)
        if (rootfsDir.exists() && !rootfsDir.renameTo(old)) TarGzExtractor.deleteTreeNoFollow(rootfsDir)
        if (!tmp.renameTo(rootfsDir)) error("rename ${tmp.path} -> ${rootfsDir.path} failed")
        TarGzExtractor.deleteTreeNoFollow(old)

        val msg = "rootfs installed in ${System.currentTimeMillis() - t0} ms: $stats"
        Log.i(TAG, msg)
        return msg
    }

    private fun writeGuestConfig(root: File) {
        val etc = File(root, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "hostname").writeText("linuxdesk\n")
        // Chromium is in Alpine's "community" repository. Pin both repos to the release the rootfs comes from.
        val branch = bundledVersion().removePrefix("alpine-").split('.').take(2).joinToString(".")
        if (branch.matches(Regex("""\d+\.\d+"""))) {
            File(etc, "apk").mkdirs()
            File(etc, "apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v$branch/main\n" +
                    "https://dl-cdn.alpinelinux.org/alpine/v$branch/community\n"
            )
        }
        File(root, "root").mkdirs()
        File(root, "tmp/.X11-unix").mkdirs()
        File(root, "tmp/shm").mkdirs()
        File(root, "opt/linuxdesk").mkdirs()
    }

    /** Copies the bundled helper scripts and the focus-bridge extension into rootfs/opt/linuxdesk. Done every session. */
    fun installHelpers(focusBridgeConfigJs: String?) {
        val dest = File(rootfsDir, "opt/linuxdesk").apply { mkdirs() }
        copyAssetDir("linuxdesk", dest)
        File(dest, "start-browser.sh").setExecutable(true, false)
        File(dest, "fit-window.sh").setExecutable(true, false)
        File(dest, "setup-packages.sh").setExecutable(true, false)
        if (focusBridgeConfigJs != null) File(dest, "focus-bridge/config.js").writeText(focusBridgeConfigJs)
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyAssetDir("$assetPath/$c", File(dest, c))
    }

    /** True once setup-packages.sh has completed in this rootfs. */
    fun packagesInstalled(): Boolean = File(rootfsDir, "etc/linuxdesk/packages-v1").isFile

    companion object { private const val TAG = "RootfsInstaller" }
}
