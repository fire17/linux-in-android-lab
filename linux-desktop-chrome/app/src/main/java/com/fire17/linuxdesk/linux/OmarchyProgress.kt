package com.fire17.linuxdesk.linux

/**
 * Turns the Omarchy first run (download → extract → setup-omarchy.sh → boot) into one overall percent and a
 * human stage line for OmarchySetupActivity. Pure Kotlin (no Android), so it is unit-tested on the JVM.
 *
 * Inputs:
 *  - [onDownload] percent of the 829 MB Arch Linux ARM tarball (ArchRootfsDownloader).
 *  - [onExtract] file count from TarGzExtractor's "N files…" lines (~25,000 files are written).
 *  - [onLine] every line setup-omarchy.sh / start-omarchy.sh prints. Two kinds matter:
 *      "LINUXDESK_STAGE <n>/<total> <text>"  explicit markers written by the scripts;
 *      pacman's non-tty output, checked 2026-11-19 in a live aarch64 run and on Tami's phone (21:14):
 *          "Packages (119) abseil-cpp-…  alsa-lib-…"      → how many packages this transaction has
 *          "installing xkeyboard-config..." / "upgrading bash..." / "removing linux-aarch64..."  → one more done
 *          "(3/119) installing xkeyboard-config"          → the same, tty form
 *          " google-chrome-158…-aarch64 downloading..."    → a download is in flight
 *          "error: failed retrieving file … 404"          → kept as the last error line for the failure view
 *      plus "[omarchy] i3 started" (boot) and "LINUXDESK_WINDOW_UP" (start-omarchy.sh) = the desktop is on screen.
 *
 * Overall bar weights: download 0–35 %, extract 35–45 %, setup 45–95 % (spread across the script's stages, and
 * inside a pacman stage by installed/total packages), boot 95–100 %.
 */
class OmarchyProgress {

    enum class Phase { STARTING, DOWNLOAD, EXTRACT, SETUP, BOOT, UP }

    data class Snapshot(
        val percent: Int,
        val stage: String,
        val detail: String,
        val phase: Phase = Phase.STARTING,
        /** Script stage n of [stageTotal] (0 = no LINUXDESK_STAGE marker seen yet). */
        val stageIndex: Int = 0,
        val stageTotal: Int = 0,
        /** The last pacman/script error line, "" when none. Shown when the session ends in ERROR. */
        val error: String = "",
    )

    @Volatile var snapshot = Snapshot(0, "Starting …", "")
        private set

    private var stageIdx = 0
    private var stageTotal = 1
    private var stageText = ""
    private var pkgTotal = 0
    private var pkgDone = 0
    private var lastError = ""

    /** Back to 0 for a new run. In place, so a SessionService that already holds this instance keeps feeding it. */
    @Synchronized fun reset() {
        stageIdx = 0; stageTotal = 1; stageText = ""; pkgTotal = 0; pkgDone = 0; lastError = ""
        snapshot = Snapshot(0, "Starting …", "")
    }

    @Synchronized fun onDownload(pct: Int) {
        val p = pct.coerceIn(0, 100)
        set(p * 35 / 100, "Downloading Arch Linux ARM ($p %)", "", Phase.DOWNLOAD)
    }

    @Synchronized fun onExtract(files: Int) {
        val f = (files.coerceAtLeast(0) * 10 / EXTRACT_FILES).coerceAtMost(10)
        set(35 + f, "Unpacking Arch Linux ARM", "$files files", Phase.EXTRACT)
    }

    /** Feeds one log line. Returns true when the line changed the snapshot. */
    @Synchronized fun onLine(raw: String): Boolean {
        val line = raw.trimEnd()
        STAGE.matchEntire(line)?.let { m ->
            stageIdx = m.groupValues[1].toInt()
            stageTotal = m.groupValues[2].toInt().coerceAtLeast(1)
            stageText = m.groupValues[3]
            pkgTotal = 0; pkgDone = 0
            set(setupPercent(), stageText, "", Phase.SETUP)
            return true
        }
        PACKAGES.find(line)?.let { m ->
            pkgTotal += m.groupValues[1].toInt()
            set(setupPercent(), setupStage(), "$pkgDone / $pkgTotal packages", Phase.SETUP)
            return true
        }
        INSTALLING.matchEntire(line)?.let { m ->
            pkgDone++
            set(setupPercent(), setupStage(),
                "${m.groupValues[1]} ${m.groupValues[2]} ($pkgDone / ${pkgTotal.coerceAtLeast(pkgDone)})", Phase.SETUP)
            return true
        }
        DOWNLOADING.matchEntire(line)?.let { m ->
            set(snapshot.percent, setupStage(), "downloading ${m.groupValues[1]}", Phase.SETUP)
            return true
        }
        EXTRACT.matchEntire(line)?.let { m ->
            onExtract(m.groupValues[1].toInt())
            return true
        }
        if (line.startsWith("error: ")) {
            // pacman's specific cause ("failed retrieving file … 404") beats its generic summary that follows it.
            if (!(line.startsWith("error: failed to commit transaction") && lastError.startsWith("error: "))) lastError = line
            set(snapshot.percent, snapshot.stage, snapshot.detail, snapshot.phase)
            return true
        }
        if (line.startsWith("[omarchy] ")) {
            val msg = line.removePrefix("[omarchy] ")
            if ((msg.contains("failed") || msg.startsWith("no browser")) && !lastError.startsWith("error: ")) lastError = line
            if (msg.startsWith("i3 started") || msg.startsWith("exec ")) {
                set(maxOf(snapshot.percent, 97), "Starting the Omarchy desktop", msg, Phase.BOOT)
            } else {
                set(snapshot.percent, snapshot.stage, msg, snapshot.phase)
            }
            return true
        }
        if (line.startsWith("LINUXDESK_WINDOW_UP")) {
            set(100, "Omarchy is up", "", Phase.UP)
            return true
        }
        return false
    }

    private fun setupStage() = stageText.ifEmpty { "Installing packages" }

    /**
     * 45..95 across the script stages; inside a stage, by packages installed when pacman reported a count. Without
     * LINUXDESK_STAGE markers (an older setup script) the whole setup is one stage of about [SETUP_PKGS] packages,
     * so the first small pacman transaction (e.g. removing the kernel, 15 packages) cannot fill the bar.
     */
    private fun setupPercent(): Int {
        val per = 50.0 / stageTotal
        val denom = if (stageIdx == 0) maxOf(pkgTotal, SETUP_PKGS) else pkgTotal
        val within = if (denom > 0) (pkgDone.toDouble() / denom).coerceIn(0.0, 1.0) else 0.0
        val done = (stageIdx - 1).coerceAtLeast(0) + within
        return (45 + per * done).toInt().coerceIn(45, 95)
    }

    private fun set(pct: Int, stage: String, detail: String, phase: Phase) {
        // Never go backwards (a later pacman transaction, or a re-run of an earlier stage, must not shrink the bar).
        val p = maxOf(pct, snapshot.percent).coerceIn(0, 100)
        val ph = if (phase.ordinal < snapshot.phase.ordinal) snapshot.phase else phase
        snapshot = Snapshot(p, stage, detail, ph, stageIdx, if (stageIdx > 0) stageTotal else 0, lastError)
    }

    companion object {
        const val EXTRACT_FILES = 25_000
        /** Measured 2026-11-19: ~146 upgrades + 15 removals + ~120–190 new packages in a full first-run setup. */
        const val SETUP_PKGS = 330
        private val STAGE = Regex("""LINUXDESK_STAGE (\d+)/(\d+) (.+)""")
        private val PACKAGES = Regex("""^Packages \((\d+)\)""")
        private val INSTALLING =
            Regex("""^(?:\(\s*\d+/\d+\)\s+)?(installing|upgrading|reinstalling|removing) (\S+?)(?:\.\.\.)?$""")
        private val EXTRACT = Regex("""^(\d+) files…$""")
        private val DOWNLOADING = Regex("""^\s*(\S+) downloading\.\.\.$""")
    }
}

/** The current session's Omarchy progress (SessionService feeds it; OmarchySetupActivity shows it). */
object OmarchyRun {
    /** One instance for the app's life: SessionService captures it at session start, so it is reset in place. */
    val progress = OmarchyProgress()
    /** True when OmarchySetupActivity opened the desktop itself, so the launcher does not open it a second time. */
    @Volatile var desktopOpenedBySetup = false
    /** True once the setup screen was shown for this run, so the launcher opens it automatically only once. */
    @Volatile var setupShown = false

    fun reset() { progress.reset(); desktopOpenedBySetup = false; setupShown = false }
}
