package com.fire17.linuxdesk.linux

import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.IOException

/**
 * Fast path for an Arch-based distro (Tami 2026-11-20: "Andromarchy was up in ~1 min"): a PREBUILT rootfs published as a
 * GitHub Release asset (`*.tar.zst`, built by the omarchy-rootfs / omamobile-rootfs workflows, setup already run, its
 * packages marker inside). One pass, nothing stored: 8 parallel HTTP Range requests ([ParallelRangeStream]) → in-order
 * stream → SHA-256 on the fly (StreamInstall) → zstd → tar into [staging].
 *
 * Shared by every distro that has a prebuilt asset (OMARCHY, OMARCHY_CLEAN, OMARCHY_MOBILE): callers pass url + sha url
 * + staging dir, then do the usual StreamInstall.swap.
 */
object PrebuiltRootfs {
    private const val TAG = "PrebuiltRootfs"
    const val REPO = "https://github.com/fire17/linux-in-android-lab/releases/download"

    /** Release asset URL for a workflow-built rootfs, e.g. asset("omarchy-rootfs-v1", "omarchy-rootfs.tar.zst"). */
    fun asset(tag: String, name: String) = "$REPO/$tag/$name"

    /**
     * Returns false, with [staging] untouched, when HEAD on [assetUrl] does not answer 200 (no release yet, offline):
     * the caller then takes its normal path. Once the download has started, every failure (IO, truncation, sha256
     * mismatch, a bad .sha256 file) deletes [staging] and THROWS, like StreamInstall.run. [sha256Url] holds
     * `sha256sum` output; its first word is the hex. [onPercent] gets 0..100 by downloaded bytes.
     */
    fun tryInstall(
        assetUrl: String,
        sha256Url: String,
        staging: File,
        onProgress: (String) -> Unit,
        onPercent: (Int) -> Unit,
    ): Boolean {
        val total = Http.headLength(assetUrl) ?: run {
            Log.i(TAG, "no prebuilt rootfs at $assetUrl; using the normal install")
            return false
        }
        if (total <= 0) return false
        val want = try {
            Http.text(sha256Url).trim().split(Regex("\\s+")).first().lowercase()
        } catch (e: Exception) {
            Log.w(TAG, "no sha256 for $assetUrl", e)
            return false
        }
        if (!want.matches(Regex("[0-9a-f]{64}"))) {
            onProgress("prebuilt rootfs: bad sha256 file at $sha256Url; using the normal install")
            return false
        }
        staging.mkdirs()
        val name = assetUrl.substringAfterLast('/')
        onProgress("Downloading + unpacking the prebuilt rootfs $name (${total shr 20} MB, 8 parallel connections)")
        val t0 = System.currentTimeMillis()
        var lastPct = -1
        val (stats, d) = ParallelRangeStream(assetUrl, total).use { raw ->
            StreamInstall.run(raw, total, staging,
                // Not closed here: StreamInstall drains (and hashes) the rest of the raw stream after the tar end.
                extract = { ins ->
                    val z = ZstdInputStream(ins).setLongMax(31)  // built with --long=27; any window up to 2 GiB
                    TarGzExtractor(staging, gzip = false).extract(z, onProgress)
                },
                verify = { if (it.sha256 == want) null else "sha256 mismatch for $name: got ${it.sha256}, want $want" },
                onBytes = { got, tot ->
                    val pct = (got * 100 / tot).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onPercent(pct)
                        if (pct % 10 == 0) onProgress("prebuilt $pct% (${got shr 20} / ${tot shr 20} MB)")
                    }
                })
        }
        val s = (System.currentTimeMillis() - t0) / 1000
        onProgress("prebuilt rootfs: sha256 OK, ${d.bytes shr 20} MB in $s s, ${stats.files} files")
        return true
    }
}
