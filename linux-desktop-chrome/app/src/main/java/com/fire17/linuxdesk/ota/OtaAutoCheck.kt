package com.fire17.linuxdesk.ota

import java.io.File

/**
 * OTA v1 — the auto-apply switch (default OFF; Tami decides). Pure JVM.
 *
 * When ON, [maybeRun] at session start checks the update URL at most once per [INTERVAL_MS] (6 h). A newer, verified
 * bundle is downloaded and ACTIVATED (on trial) right away, but it only takes effect at the NEXT session start, because
 * this session's installHelpers already ran. It never restarts anything. When OFF it does nothing: only the Check button
 * in the Updates screen acts. Never throws; runs on its own daemon thread.
 */
object OtaAutoCheck {
    const val INTERVAL_MS = 6L * 60 * 60 * 1000

    /** @return the reason for not running, or null if a check was started. */
    fun maybeRun(
        store: BundleStore,
        enabled: Boolean,
        baseUrl: String,
        now: Long = System.currentTimeMillis(),
        async: Boolean = true,
        /** Called with the activated bundle version (the session shows a notification + a sys.logs line). */
        onApplied: (Long) -> Unit = {},
    ): String? {
        if (!enabled) return "off"
        val stamp = File(store.root, "last_auto_check")
        val last = try { stamp.readText().trim().toLong() } catch (_: Exception) { 0L }
        if (now - last in 0 until INTERVAL_MS) return "too_soon"
        // A bundle still on trial must be judged before another one replaces it.
        if (store.state().optBoolean("trial")) return "trial_pending"
        try { store.root.mkdirs(); stamp.writeText(now.toString()) } catch (_: Exception) { return "io" }
        val run = Runnable {
            val v = try { checkAndStage(store, baseUrl) } catch (_: Throwable) { null }
            if (v != null) try { onApplied(v) } catch (_: Throwable) {}
        }
        if (async) Thread(run, "LinuxDeskOtaAutoCheck").apply { isDaemon = true; start() } else run.run()
        return null
    }

    /** @return the activated version, or null when nothing was applied. */
    internal fun checkAndStage(store: BundleStore, baseUrl: String): Long? {
        val r = store.checkRemote(baseUrl + "manifest.json")
        if (r !is BundleVerifier.Result.Ok) {
            store.ledger("auto_check", org.json.JSONObject().put("result", (r as BundleVerifier.Result.Rejected).reason))
            return null
        }
        store.downloadAndStage(baseUrl + "manifest.json", baseUrl + "bundle.zip", channel = "https_auto")
        return store.activateStaged(consentMode = "auto")
    }

    /** The one-line text for the notification and the sys.logs line. */
    fun readyLine(version: Long) = "Update $version ready, applies at next start"
}
