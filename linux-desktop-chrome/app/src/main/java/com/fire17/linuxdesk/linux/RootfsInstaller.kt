package com.fire17.linuxdesk.linux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs a distro's rootfs into its own directory (see [Distro]), then (separately) the desktop packages with
 * the distro's package manager inside proot.
 *
 *  - ALPINE (default, unchanged): the bundled Alpine minirootfs from the APK assets → filesDir/rootfs.
 *  - OMARCHY: the official Arch Linux ARM aarch64 tarball, downloaded on first use with a progress bar and
 *    verified (see [ArchRootfsDownloader]) → filesDir/rootfs-arch. The kernel, firmware and modules are not
 *    extracted (proot never uses them).
 *
 * Adapted from linux-in-app's RootfsInstaller. One change: trees are deleted with
 * TarGzExtractor.deleteTreeNoFollow, never with File.deleteRecursively. The latter follows the guest's
 * absolute symlinks into the HOST filesystem.
 */
class RootfsInstaller(private val context: Context, val distro: Distro = Distro.ALPINE) {

    val rootfsDir: File get() = distro.rootfsDir(context)
    private val marker: File get() = File(rootfsDir, ".linuxdesk-installed")

    fun assetForDevice(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: error("Unsupported ABI ${Build.SUPPORTED_ABIS.joinToString()}; this build ships arm64-v8a and x86_64 only")
        return if (abi == "arm64-v8a") "rootfs/alpine-aarch64.rootfs.bin" else "rootfs/alpine-x86_64.rootfs.bin"
    }

    private fun bundledVersion(): String =
        try { context.assets.open("rootfs/VERSION").bufferedReader().readText().trim() } catch (_: Exception) { "unknown" }

    /** The version string the marker must hold. Arch is a rolling release: any completed install counts. */
    private fun wantedVersion(): String = when (distro) {
        Distro.ALPINE -> bundledVersion()
        Distro.OMARCHY -> ArchRootfsDownloader.VERSION
    }

    fun isInstalled(): Boolean =
        marker.isFile && marker.readText().trim() == wantedVersion() && File(rootfsDir, "etc").isDirectory

    /**
     * Blocking; call off the main thread. [onPercent] gets the download percent (0..100) for OMARCHY's first run,
     * and -1 when the download phase is over (extracting).
     */
    fun ensureInstalled(onProgress: (String) -> Unit = {}, onPercent: (Int) -> Unit = {}): String {
        if (isInstalled()) return "rootfs already installed (${distro.id}, ${wantedVersion()})"
        val tmp = File(context.filesDir, "${distro.dirName}.partial")
        TarGzExtractor.deleteTreeNoFollow(tmp)
        tmp.mkdirs()
        val t0 = System.currentTimeMillis()
        val stats = when (distro) {
            Distro.ALPINE -> {
                val asset = assetForDevice()
                onProgress("Extracting $asset …")
                context.assets.open(asset).use { TarGzExtractor(tmp).extract(it, onProgress) }
            }
            Distro.OMARCHY -> {
                if (!Distro.omarchySupported()) error("Omarchy (Arch Linux ARM) needs an arm64-v8a device")
                val tarball = ArchRootfsDownloader(context).ensureDownloaded(onProgress, onPercent)
                onPercent(-1)
                onProgress("Extracting ${tarball.name} (about 0.7 GB; kernel, firmware and modules are skipped) …")
                val s = tarball.inputStream().use {
                    TarGzExtractor(tmp, ArchRootfsDownloader.SKIP_PREFIXES).extract(it, onProgress)
                }
                // The extracted tree is the real copy now; free 0.8 GB. A reinstall downloads again.
                tarball.delete()
                s
            }
        }
        writeGuestConfig(tmp)
        File(tmp, ".linuxdesk-installed").writeText(wantedVersion() + "\n")

        val old = File(context.filesDir, "${distro.dirName}.old")
        TarGzExtractor.deleteTreeNoFollow(old)
        if (rootfsDir.exists() && !rootfsDir.renameTo(old)) TarGzExtractor.deleteTreeNoFollow(rootfsDir)
        if (!tmp.renameTo(rootfsDir)) error("rename ${tmp.path} -> ${rootfsDir.path} failed")
        TarGzExtractor.deleteTreeNoFollow(old)

        val msg = "rootfs (${distro.id}) installed in ${System.currentTimeMillis() - t0} ms: $stats"
        Log.i(TAG, msg)
        return msg
    }

    private fun writeGuestConfig(root: File) {
        val etc = File(root, "etc").apply { mkdirs() }
        // delete() first: Arch ships resolv.conf as a symlink to /run/systemd/resolve/resolv.conf, and writing
        // through it would follow the link.
        File(etc, "resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "hostname").writeText("linuxdesk\n")
        if (distro == Distro.ALPINE) {
            // Chromium is in Alpine's "community" repository. Pin both repos to the release the rootfs comes from.
            val branch = bundledVersion().removePrefix("alpine-").split('.').take(2).joinToString(".")
            if (branch.matches(Regex("""\d+\.\d+"""))) {
                File(etc, "apk").mkdirs()
                File(etc, "apk/repositories").writeText(
                    "https://dl-cdn.alpinelinux.org/alpine/v$branch/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/v$branch/community\n"
                )
            }
        }
        // OMARCHY: pacman.conf (DisableSandbox, DownloadUser, [omarchy] repo) is edited by setup-omarchy.sh inside
        // the guest, so the whole pacman setup lives in one script that can be read and re-run.
        File(root, "root").mkdirs()
        File(root, "tmp/.X11-unix").mkdirs()
        File(root, "tmp/shm").mkdirs()
        File(root, "opt/linuxdesk").mkdirs()
    }

    /** Copies the bundled helper scripts and the focus-bridge extension into rootfs/opt/linuxdesk. Done every session. */
    fun installHelpers(focusBridgeConfigJs: String?) {
        val dest = File(rootfsDir, "opt/linuxdesk").apply { mkdirs() }
        copyAssetDir("linuxdesk", dest)
        for (n in listOf("start-browser.sh", "fit-window.sh", "setup-packages.sh",
                "setup-omarchy.sh", "start-omarchy.sh", "setup-genie-arch.sh")) {
            File(dest, n).takeIf { it.isFile }?.setExecutable(true, false)
        }
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

    /** True once the distro's package script (setup-packages.sh / setup-omarchy.sh) has completed in this rootfs. */
    fun packagesInstalled(): Boolean = File(rootfsDir, distro.packagesMarker).isFile

    companion object { private const val TAG = "RootfsInstaller" }
}
