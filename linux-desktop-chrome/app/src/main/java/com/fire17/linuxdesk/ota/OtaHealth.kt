package com.fire17.linuxdesk.ota

/**
 * OTA v1 — the per-session trial verdict (notes/ota-plan.md §3.3). Pure JVM: SessionService passes the signals as
 * lambdas, so this is unit-tested without Android.
 *
 * [start] runs at session start, BEFORE installHelpers (so an APK-upgrade clear or a rollback happens before the
 * overlay). If a trial is running it watches on a daemon thread:
 *  - the browser window comes up → wait up to [deskdGraceMs] for deskd (when wanted) → reportHealth(true, deskdUp)
 *  - the browser fails (BROWSER_EXITED / ERROR) or nothing happens within [timeoutMs] → reportHealth(false, …) → rollback
 *  - the user stops the session first → [BundleStore.abandonTrialSession]: no verdict, the next session re-tries
 * `pingOk` = the session came up (the browser window appeared); `deskdUp` = deskd's relay is connected, or Genie
 * access is OFF (deskd is not run, so it cannot be judged).
 */
object OtaHealth {

    fun start(
        store: BundleStore,
        windowUp: () -> Boolean,
        failed: () -> Boolean,
        deskdUp: () -> Boolean,
        stopping: () -> Boolean,
        timeoutMs: Long = 10 * 60_000L,
        deskdGraceMs: Long = 30_000L,
        async: Boolean = true,
        /** A real sys.ping round-trip to deskd (true = ok reply). Default: trust [deskdUp] alone (pre-round-3 behaviour). */
        deskdPing: () -> Boolean = { true },
    ) {
        try {
            store.beginSession()
            if (!store.state().optBoolean("trial")) return
        } catch (_: Throwable) {
            return
        }
        val run = Runnable {
            try { judge(store, windowUp, failed, deskdUp, stopping, timeoutMs, deskdGraceMs, deskdPing) } catch (_: Throwable) {}
        }
        if (async) Thread(run, "LinuxDeskOtaHealth").apply { isDaemon = true; start() } else run.run()
    }

    private fun judge(
        store: BundleStore, windowUp: () -> Boolean, failed: () -> Boolean, deskdUp: () -> Boolean,
        stopping: () -> Boolean, timeoutMs: Long, deskdGraceMs: Long, deskdPing: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!windowUp() && !failed() && !stopping() && System.currentTimeMillis() < end) Thread.sleep(250)
        if (stopping() && !windowUp()) { store.abandonTrialSession(); return }
        if (!windowUp()) { store.reportHealth(pingOk = false, deskdUp = deskdUp()); return }
        val dEnd = System.currentTimeMillis() + deskdGraceMs
        while (!deskdUp() && !stopping() && System.currentTimeMillis() < dEnd) Thread.sleep(250)
        if (stopping() && !deskdUp()) { store.abandonTrialSession(); return }
        // deskd up = relay connected AND a sys.ping round-trip answers ok (retried 3× so one slow reply is not a rollback).
        val up = deskdUp() && (1..3).any { i -> deskdPing() || run { if (i < 3) Thread.sleep(1_000); false } }
        store.reportHealth(pingOk = true, deskdUp = up)
    }
}
