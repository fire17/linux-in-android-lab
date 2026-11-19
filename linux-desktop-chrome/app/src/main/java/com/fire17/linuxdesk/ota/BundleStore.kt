package com.fire17.linuxdesk.ota

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * OTA v1 — on-phone store for signed guest bundles (notes/ota-plan.md §2a, §3.3, §4). Pure JVM (java.io/java.net/
 * org.json), so it unit-tests without Android.
 *
 * Layout under `filesDir/ota/`:
 *  - `staged/`      verified, not yet active (manifest.json + files)
 *  - `active/`      the bundle RootfsInstaller overlays on top of the APK assets ("bundle 0")
 *  - `prev/`        the previous active bundle (rollback target; N = 2)
 *  - `state.json`   {highest, active, prev, trial, trial_started, app_code_at_apply}; written tmp + rename
 *  - `ledger.jsonl` append-only, schema `ld-ota/1`
 *
 * Commit points are directory renames. A crash between "active → prev" and "staged → active" leaves no `active/`,
 * which simply means bundle 0 (APK assets) — always safe.
 *
 * Trial / rollback: [activateStaged] sets `trial`. [beginSession] at session start: if a trial session already began
 * and never reported health (the app died), it rolls back. [reportHealth] (sys.ping ok + deskd up) clears the trial or
 * rolls back. NOTE: the SessionService/GenieRuntime call sites for beginSession/reportHealth are NOT wired yet (OPEN).
 */
class BundleStore(
    filesDir: File,
    private val appVersionCode: Long,
    private val appVersionName: String = "",
    private val keys: List<String> = BundleVerifier.PUBLIC_KEYS,
) {
    val root = File(filesDir, "ota")
    val staged = File(root, "staged")
    val active = File(root, "active")
    val prev = File(root, "prev")
    private val stateFile = File(root, "state.json")
    val ledgerFile = File(root, "ledger.jsonl")

    companion object {
        const val MAX_BUNDLE_BYTES = 20L * 1024 * 1024
        const val MANIFEST = "manifest.json"
        const val LEDGER_ROTATE_BYTES = 2L * 1024 * 1024
        private val lock = Any()
    }

    class OtaException(val reason: String, msg: String) : Exception("$reason: $msg")

    // ---------------------------------------------------------------- state

    fun state(): JSONObject = try {
        JSONObject(stateFile.readText())
    } catch (_: Exception) {
        JSONObject().put("highest", 0).put("active", 0).put("prev", 0).put("trial", false).put("trial_started", false)
    }

    private fun writeState(s: JSONObject) {
        root.mkdirs()
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(s.toString())
        if (!tmp.renameTo(stateFile)) throw OtaException("IO", "state rename failed")
    }

    fun highestAccepted(): Long = state().optLong("highest", 0)
    fun activeVersion(): Long = if (File(active, MANIFEST).isFile) state().optLong("active", 0) else 0

    // ---------------------------------------------------------------- download + stage

    /** Downloads manifest + zip over HTTPS, verifies, and stages. Blocking; call off the main thread. */
    fun downloadAndStage(manifestUrl: String, zipUrl: String, channel: String = "https"): BundleVerifier.Manifest {
        val mBytes = httpsGet(manifestUrl, BundleVerifier.MAX_MANIFEST_BYTES.toLong())
        val zBytes = httpsGet(zipUrl, MAX_BUNDLE_BYTES)
        ledger("downloaded", JSONObject().put("channel", channel).put("bytes", zBytes.size))
        return stage(mBytes, zBytes.inputStream(), channel)
    }

    /** sys.ota.status: state + the newest [tail] ledger rows (rows hold no keys/tokens by construction). */
    fun statusJson(tail: Int, autoApply: Boolean): JSONObject {
        val s = state()
        return JSONObject().put("ok", true).put("app_code", appVersionCode).put("app", appVersionName)
            .put("active", activeVersion()).put("prev", s.optLong("prev", 0)).put("highest", s.optLong("highest", 0))
            .put("trial", s.optBoolean("trial")).put("auto_apply", autoApply)
            .put("ledger", org.json.JSONArray(ledgerRows(tail)))
    }

    /** sys.ota.check: manifest-only check; never downloads the bundle, never stages or installs. */
    fun checkJson(manifestUrl: String): JSONObject = when (val r = checkRemote(manifestUrl)) {
        is BundleVerifier.Result.Ok -> JSONObject().put("ok", true).put("available", true)
            .put("version", r.manifest.version).put("files", r.manifest.files.size).put("signature", "ok")
            .put("active", activeVersion())
        is BundleVerifier.Result.Rejected -> JSONObject().put("ok", true).put("available", false)
            .put("reason", r.reason).put("active", activeVersion())
    }

    /** "Check": fetches only the manifest and verifies it (signature, anti-downgrade, min_app). Nothing is stored. */
    fun checkRemote(manifestUrl: String): BundleVerifier.Result =
        try {
            BundleVerifier.verify(httpsGet(manifestUrl, BundleVerifier.MAX_MANIFEST_BYTES.toLong()), highestAccepted(), appVersionCode, keys)
        } catch (e: OtaException) {
            BundleVerifier.Result.Rejected(e.reason, e.message ?: "")
        } catch (e: Exception) {
            BundleVerifier.Result.Rejected("NETWORK", e.message ?: e.javaClass.simpleName)
        }

    /** Verifies the manifest signature/version, extracts the zip into staged/, checks every file's sha256. */
    fun stage(manifestBytes: ByteArray, zip: InputStream, channel: String = "local"): BundleVerifier.Manifest =
        synchronized(lock) {
            val r = BundleVerifier.verify(manifestBytes, highestAccepted(), appVersionCode, keys)
            if (r is BundleVerifier.Result.Rejected) {
                ledger("rejected", JSONObject().put("channel", channel).put("reason", r.reason).put("detail", r.detail))
                throw OtaException(r.reason, r.detail)
            }
            val m = (r as BundleVerifier.Result.Ok).manifest
            val tmp = File(root, "staged.partial")
            deleteTree(tmp); tmp.mkdirs()
            try {
                extractChecked(zip, tmp, m.files)
                File(tmp, MANIFEST).writeBytes(manifestBytes)
            } catch (e: Exception) {
                deleteTree(tmp)
                val reason = (e as? OtaException)?.reason ?: "EXTRACT_FAILED"
                ledger("rejected", JSONObject().put("channel", channel).put("to", ver(m.version)).put("reason", reason)
                    .put("detail", e.message ?: ""))
                throw e
            }
            deleteTree(staged)
            if (!tmp.renameTo(staged)) throw OtaException("IO", "staged rename failed")
            ledger("verified", JSONObject().put("channel", channel).put("to", ver(m.version)).put("files", m.files.size))
            m
        }

    private fun extractChecked(zip: InputStream, dest: File, want: Map<String, String>) {
        val seen = HashSet<String>()
        var total = 0L
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (!BundleVerifier.isSafeRelPath(name)) throw OtaException("UNSAFE_PATH", name)
                val wantSha = want[name] ?: throw OtaException("UNLISTED_FILE", name)
                if (!seen.add(name)) throw OtaException("DUPLICATE_FILE", name)
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = z.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BUNDLE_BYTES) throw OtaException("TOO_LARGE", "uncompressed > $MAX_BUNDLE_BYTES")
                    buf.write(chunk, 0, n)
                }
                val bytes = buf.toByteArray()
                val got = BundleVerifier.sha256Hex(bytes)
                if (got != wantSha) throw OtaException("SHA_MISMATCH", name)
                val out = File(dest, name)
                // Paths are checked; also confirm canonical containment (defence in depth).
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) throw OtaException("UNSAFE_PATH", name)
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        }
        val missing = want.keys - seen
        if (missing.isNotEmpty()) throw OtaException("MISSING_FILE", missing.first())
    }

    // ---------------------------------------------------------------- activate / rollback

    /** staged → active (old active → prev). Starts a trial. Returns the activated version. */
    fun activateStaged(consentMode: String = "tap"): Long = synchronized(lock) {
        val mf = File(staged, MANIFEST)
        if (!mf.isFile) throw OtaException("NOTHING_STAGED", "")
        // Re-verify at the commit point: staged/ lives in app-private storage the guest can reach.
        val m = verifyDir(staged, highestAccepted()) ?: throw OtaException("STAGED_TAMPERED", "")
        val s = state()
        val from = s.optLong("active", 0)
        deleteTree(prev)
        if (active.exists() && !active.renameTo(prev)) throw OtaException("IO", "active→prev rename failed")
        if (!staged.renameTo(active)) throw OtaException("IO", "staged→active rename failed")
        s.put("prev", if (prev.exists()) from else 0)
        s.put("active", m.version)
        s.put("highest", maxOf(s.optLong("highest", 0), m.version))
        s.put("trial", true).put("trial_started", false).put("app_code_at_apply", appVersionCode)
        writeState(s)
        ledger("applied", JSONObject().put("from", ver(from)).put("to", ver(m.version))
            .put("consent", JSONObject().put("mode", consentMode)))
        m.version
    }

    /** Call at session start (before installHelpers). Rolls back a trial whose previous session never reported health. */
    fun beginSession(): Unit = synchronized(lock) {
        val s = state()
        // Plan §3.2: an APK newer than the one the bundle was applied under ships a newer bundle 0 → it wins.
        // Clear active + prev (once: app_code_at_apply is reset by the clear). `highest` is kept (anti-downgrade).
        val appliedUnder = s.optLong("app_code_at_apply", 0)
        if (File(active, MANIFEST).isFile && appliedUnder in 1 until appVersionCode) {
            clearForApkUpgrade(appliedUnder); return@synchronized
        }
        if (!s.optBoolean("trial")) return@synchronized
        if (s.optBoolean("trial_started")) {
            rollback("trial session ended without a health report (app died?)")
        } else {
            s.put("trial_started", true); writeState(s)
        }
    }

    /** Call once per session when the health check finishes. Returns true if the trial (if any) was kept. */
    fun reportHealth(pingOk: Boolean, deskdUp: Boolean): Boolean = synchronized(lock) {
        val s = state()
        if (!s.optBoolean("trial")) return@synchronized true
        val checks = JSONObject().put("sys_ping", pingOk).put("deskd_up", deskdUp)
        if (pingOk && deskdUp) {
            s.put("trial", false).put("trial_started", false); writeState(s)
            ledger("health_ok", JSONObject().put("to", ver(s.optLong("active")))
                .put("health", JSONObject().put("result", "ok").put("checks", checks)))
            true
        } else {
            ledger("health_fail", JSONObject().put("to", ver(s.optLong("active")))
                .put("health", JSONObject().put("result", "fail").put("checks", checks)))
            rollback("health check failed")
            false
        }
    }

    /** active → removed, prev → active (or bundle 0 if there is no prev). Local; `highest` is NOT lowered. */
    /** The session was stopped by the user before a verdict: keep the trial, but let the next session judge it. */
    fun abandonTrialSession(): Unit = synchronized(lock) {
        val s = state()
        if (s.optBoolean("trial") && s.optBoolean("trial_started")) { s.put("trial_started", false); writeState(s) }
    }

    private fun clearForApkUpgrade(appliedUnder: Long) {
        val s = state()
        val from = s.optLong("active", 0)
        deleteTree(active); deleteTree(prev); deleteTree(staged)
        s.put("active", 0).put("prev", 0).put("trial", false).put("trial_started", false).put("app_code_at_apply", 0)
        writeState(s)
        ledger("apk_installed", JSONObject().put("from", ver(from)).put("to", ver(0))
            .put("delivery", "apk").put("reason", "apk upgrade $appliedUnder → $appVersionCode: bundle 0 wins"))
    }

    fun rollback(reason: String): Unit = synchronized(lock) {
        val s = state()
        val from = s.optLong("active", 0)
        val bad = File(root, "rejected")
        deleteTree(bad)
        if (active.exists()) active.renameTo(bad)
        val to = if (prev.exists() && prev.renameTo(active)) s.optLong("prev", 0) else 0
        s.put("active", to).put("prev", 0).put("trial", false).put("trial_started", false)
        writeState(s)
        deleteTree(bad)
        ledger("rolled_back", JSONObject().put("from", ver(from)).put("to", ver(to)).put("rollback", true)
            .put("rollback_reason", reason))
    }

    // ---------------------------------------------------------------- RootfsInstaller hook

    /**
     * Copies every file of ota/active (minus manifest.json) over [dest] (rootfs/opt/linuxdesk, already filled from the APK
     * assets). Re-verifies the signature and every sha256 first; on any mismatch it copies nothing (bundle 0 stays)
     * and rolls back. Never throws: OTA must never break a session. Returns the number of files copied.
     */
    fun overlayActive(dest: File): Int = try {
        synchronized(lock) {
            if (!File(active, MANIFEST).isFile) return@synchronized 0
            val m = verifyDir(active, 0)
            if (m == null) {
                rollback("active bundle failed re-verification")
                return@synchronized 0
            }
            for (p in m.files.keys) {
                val out = File(dest, p)
                out.parentFile?.mkdirs()
                if (out.exists()) out.delete() // never write through a symlink the guest planted
                File(active, p).copyTo(out, overwrite = true)
                if (p.endsWith(".sh")) out.setExecutable(true, false)
            }
            m.files.size
        }
    } catch (_: Exception) {
        0
    }

    /** Signature + min_app + every file's sha256. [floor] = version must be > floor (0 = any). */
    private fun verifyDir(dir: File, floor: Long): BundleVerifier.Manifest? {
        val bytes = try { File(dir, MANIFEST).readBytes() } catch (_: Exception) { return null }
        val r = BundleVerifier.verify(bytes, floor, appVersionCode, keys)
        val m = (r as? BundleVerifier.Result.Ok)?.manifest ?: return null
        for ((p, sha) in m.files) {
            val f = File(dir, p)
            if (!f.isFile || BundleVerifier.sha256Hex(f.readBytes()) != sha) return null
        }
        return m
    }

    // ---------------------------------------------------------------- ledger

    fun ledger(event: String, extra: JSONObject = JSONObject()) {
        try {
            root.mkdirs()
            if (ledgerFile.length() > LEDGER_ROTATE_BYTES) ledgerFile.renameTo(File(root, "ledger.1.jsonl"))
            val row = JSONObject()
                .put("schema", "ld-ota/1")
                .put("event_id", UUID.randomUUID().toString())
                .put("ts", java.time.OffsetDateTime.now().toString())
                .put("event", event)
                .put("layer", "guest")
                .put("delivery", "ota")
            for (k in extra.keys()) row.put(k, extra.get(k))
            ledgerFile.appendText(row.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    fun ledgerRows(limit: Int = 50): List<JSONObject> = try {
        ledgerFile.readLines().takeLast(limit).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.reversed()
    } catch (_: Exception) {
        emptyList()
    }

    private fun ver(bundle: Long) = JSONObject().put("app", appVersionName).put("app_code", appVersionCode).put("bundle", bundle)

    // ---------------------------------------------------------------- helpers

    private fun httpsGet(url: String, cap: Long): ByteArray {
        val u = URL(url)
        if (u.protocol != "https") throw OtaException("NOT_HTTPS", url)
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.instanceFollowRedirects = true // HttpURLConnection never follows https→http
        try {
            if (c.responseCode != 200) throw OtaException("HTTP_${c.responseCode}", url)
            if (c.url.protocol != "https") throw OtaException("NOT_HTTPS", c.url.toString())
            val out = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val b = ByteArray(32 * 1024)
                while (true) {
                    val n = inp.read(b)
                    if (n < 0) break
                    if (out.size() + n > cap) throw OtaException("TOO_LARGE", url)
                    out.write(b, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** No-follow delete: symlinks are unlinked, never traversed (same rule as TarGzExtractor.deleteTreeNoFollow). */
    private fun deleteTree(f: File) {
        val p = f.toPath()
        if (!java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (java.nio.file.Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            f.listFiles()?.forEach { deleteTree(it) }
        }
        java.nio.file.Files.deleteIfExists(p)
    }
}
