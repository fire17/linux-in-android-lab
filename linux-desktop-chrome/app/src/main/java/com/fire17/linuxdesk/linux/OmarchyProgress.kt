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
 *      pacman's non-tty output, checked 2026-11-19 in a live aarch64 run:
 *          "Packages (119) abseil-cpp-…  alsa-lib-…"      → how many packages this transaction has
 *          "installing xkeyboard-config..." / "upgrading bash..."  → one more done
 *          " google-chrome-158…-aarch64 downloading..."    → a download is in flight
 *      plus "LINUXDESK_WINDOW_UP" (start-omarchy.sh / fit-window.sh) = the desktop is on screen.
 *
 * Overall bar weights: download 0–35 %, extract 35–45 %, setup 45–95 % (spread across the script's stages, and
 * inside a pacman stage by installed/total packages), boot 95–100 %.
 */
class OmarchyProgress {

    data class Snapshot(val percent: Int, val stage: String, val detail: String)

    @Volatile var snapshot = Snapshot(0, "Starting …", "")
        private set

    private var stageIdx = 0
    private var stageTotal = 1
    private var stageText = ""
    private var pkgTotal = 0
    private var pkgDone = 0

    fun onDownload(pct: Int) {
        val p = pct.coerceIn(0, 100)
        set(p * 35 / 100, "Downloading Arch Linux ARM ($p %)", "")
    }

    fun onExtract(files: Int) {
        val f = (files.coerceAtLeast(0) * 10 / EXTRACT_FILES).coerceAtMost(10)
        set(35 + f, "Unpacking Arch Linux ARM", "$files files")
    }

    /** Feeds one log line. Returns true when the line changed the snapshot. */
    fun onLine(raw: String): Boolean {
        val line = raw.trimEnd()
        STAGE.matchEntire(line)?.let { m ->
            stageIdx = m.groupValues[1].toInt()
            stageTotal = m.groupValues[2].toInt().coerceAtLeast(1)
            stageText = m.groupValues[3]
            pkgTotal = 0; pkgDone = 0
            set(setupPercent(), stageText, "")
            return true
        }
        PACKAGES.find(line)?.let { m ->
            pkgTotal += m.groupValues[1].toInt()
            set(setupPercent(), stageText, "$pkgDone / $pkgTotal packages")
            return true
        }
        INSTALLING.matchEntire(line)?.let { m ->
            pkgDone++
            set(setupPercent(), stageText, "${m.groupValues[1]} ${m.groupValues[2]} ($pkgDone / ${pkgTotal.coerceAtLeast(pkgDone)})")
            return true
        }
        DOWNLOADING.matchEntire(line)?.let { m ->
            set(snapshot.percent, stageText.ifEmpty { "Downloading packages" }, "downloading ${m.groupValues[1]}")
            return true
        }
        EXTRACT.matchEntire(line)?.let { m ->
            onExtract(m.groupValues[1].toInt())
            return true
        }
        if (line.startsWith("[omarchy] ")) {
            set(snapshot.percent, snapshot.stage, line.removePrefix("[omarchy] "))
            return true
        }
        if (line.startsWith("LINUXDESK_WINDOW_UP")) {
            set(100, "Omarchy is up", "")
            return true
        }
        return false
    }

    /** 45..95 across the script stages; inside a stage, by packages installed when pacman reported a count. */
    private fun setupPercent(): Int {
        val per = 50.0 / stageTotal
        val within = if (pkgTotal > 0) (pkgDone.toDouble() / pkgTotal).coerceIn(0.0, 1.0) else 0.0
        val done = (stageIdx - 1).coerceAtLeast(0) + within
        return (45 + per * done).toInt().coerceIn(45, 95)
    }

    private fun set(pct: Int, stage: String, detail: String) {
        // Never go backwards (a later pacman transaction must not shrink the bar).
        snapshot = Snapshot(maxOf(pct, snapshot.percent).coerceIn(0, 100), stage, detail)
    }

    companion object {
        const val EXTRACT_FILES = 25_000
        private val STAGE = Regex("""LINUXDESK_STAGE (\d+)/(\d+) (.+)""")
        private val PACKAGES = Regex("""^Packages \((\d+)\)""")
        private val INSTALLING = Regex("""^(installing|upgrading|reinstalling) (\S+?)\.\.\.$""")
        private val EXTRACT = Regex("""^(\d+) files…$""")
        private val DOWNLOADING = Regex("""^\s*(\S+) downloading\.\.\.$""")
    }
}

/** The current session's Omarchy progress (SessionService feeds it; OmarchySetupActivity shows it). */
object OmarchyRun {
    @Volatile var progress = OmarchyProgress()
        private set
    /** True when OmarchySetupActivity opened the desktop itself, so the launcher does not open it a second time. */
    @Volatile var desktopOpenedBySetup = false

    fun reset() { progress = OmarchyProgress(); desktopOpenedBySetup = false }
}
