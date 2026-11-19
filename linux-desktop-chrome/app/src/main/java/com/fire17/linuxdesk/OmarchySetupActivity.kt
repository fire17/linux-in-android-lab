package com.fire17.linuxdesk

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.fire17.linuxdesk.SessionBus.State
import com.fire17.linuxdesk.linux.OmarchyProgress.Phase
import com.fire17.linuxdesk.linux.OmarchyRun

/**
 * Full-screen "Omarchy setup" screen (Tami AC21A217: the install and the boot must be VISIBLE). Shows the
 * whole first run live: the Arch Linux ARM download, the unpack, every setup-omarchy.sh stage with pacman's
 * package-by-package progress, then the boot (X server, i3, Chrome). A big progress bar on top
 * (OmarchyProgress: download 0–35, unpack 35–45, setup 45–95, boot 95–100), the current stage and package in
 * big text, and a live tail of the last [TAIL_LINES] session log lines below.
 *
 * Opened by the launcher whenever an Omarchy session is installing. It switches to the Linux display
 * (DesktopActivity) by itself as soon as the X server is coming up, so the boot is watched on the real desktop:
 * the i3 bar, wallpaper and theme appear there. When setup fails, the error line and a Retry button
 * (SessionService ACTION_START: the finished steps are kept, setup-omarchy.sh is idempotent) are shown.
 * Built in code; strings in res/values{,-iw}/strings_omarchy.xml. Colors are Omarchy's tokyo-night.
 */
class OmarchySetupActivity : Activity(), SessionBus.Listener {

    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var stage: TextView
    private lateinit var detail: TextView
    private lateinit var state: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorLine: TextView
    private lateinit var errorTitle: TextView
    private lateinit var retryBtn: Button
    private lateinit var retrying: TextView
    private lateinit var log: TextView
    private val tail = ArrayDeque<String>()
    private var desktopOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OmarchyRun.setupShown = true
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            setText(R.string.ld_omarchy_title)
            setTextColor(ACCENT); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            setText(R.string.ld_omarchy_subtitle)
            setTextColor(MUTED_FG); textSize = 14f
        })
        pct = TextView(this).apply {
            setTextColor(FG); textSize = 56f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, dp(4))
        }
        root.addView(pct)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(LIGHTER_BG)
            scaleY = 3f
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        stage = TextView(this).apply {
            setTextColor(FG); textSize = 20f; setPadding(0, dp(16), 0, 0)
        }
        root.addView(stage)
        detail = TextView(this).apply {
            setTextColor(GREEN); textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, 0)
            textDirection = View.TEXT_DIRECTION_LTR
            maxLines = 2
        }
        root.addView(detail)
        state = TextView(this).apply {
            setTextColor(MUTED_FG); textSize = 12f; setPadding(0, dp(8), 0, dp(8))
            maxLines = 2
        }
        root.addView(state)

        // Failure view: the error line + Retry. Hidden until the session ends in ERROR.
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ERROR_BG)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        errorTitle = TextView(this).apply {
            setText(R.string.ld_omarchy_failed)
            setTextColor(RED); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        }
        errorBox.addView(errorTitle)
        errorLine = TextView(this).apply {
            setTextColor(FG); textSize = 12f; typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(6))
        }
        errorBox.addView(errorLine)
        errorBox.addView(TextView(this).apply {
            setText(R.string.ld_omarchy_failed_hint)
            setTextColor(MUTED_FG); textSize = 13f
        })
        retryBtn = Button(this).apply {
            setText(R.string.ld_omarchy_retry)
            setOnClickListener { retry() }
        }
        errorBox.addView(retryBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.END })
        root.addView(errorBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // "Retrying from step N: <title>" while a Retry runs (the stage that failed last time; done ones are skipped).
        retrying = TextView(this).apply {
            setTextColor(YELLOW); textSize = 14f; setPadding(0, dp(2), 0, dp(6))
            visibility = View.GONE
        }
        root.addView(retrying)

        root.addView(TextView(this).apply {
            setText(R.string.ld_omarchy_log_heading)
            setTextColor(MUTED_FG); textSize = 12f; setPadding(0, dp(4), 0, dp(4))
        })
        // The live tail: always the last TAIL_LINES lines, so no scrolling is needed to follow it.
        log = TextView(this).apply {
            setTextColor(LOG_FG); textSize = 10.5f; typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.BOTTOM
            setTextIsSelectable(true)
            setBackgroundColor(DARKER_BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(log, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            setText(R.string.ld_omarchy_open_desktop)
            setOnClickListener { openDesktop() }
        })
        buttons.addView(Button(this).apply {
            setText(R.string.ld_omarchy_back)
            setOnClickListener {
                startActivity(Intent(this@OmarchySetupActivity, LauncherActivity::class.java)
                    .putExtra(LauncherActivity.EXTRA_STAY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        })
        root.addView(buttons)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        SessionBus.addListener(this)
        tail.clear()
        SessionBus.logText().lineSequence().toList().takeLast(TAIL_LINES).forEach { tail.addLast(it) }
        log.text = tail.joinToString("\n")
        onState(SessionBus.snapshot)
    }

    override fun onStop() {
        SessionBus.removeListener(this)
        super.onStop()
    }

    override fun onState(s: SessionBus.Snapshot) {
        state.text = getString(R.string.ld_state_fmt, s.state.name, s.message.lineSequence().firstOrNull().orEmpty())
        render()
        when (s.state) {
            // The boot is shown on the real Linux display: i3, its bar, the wallpaper and the theme come up there.
            State.STARTING_X, State.WAITING_FOR_DISPLAY, State.STARTING_BROWSER, State.RUNNING ->
                if (!desktopOpened) openDesktop()
            else -> {}
        }
        showError(s)
    }

    override fun onLog(line: String) {
        tail.addLast(line)
        while (tail.size > TAIL_LINES) tail.removeFirst()
        log.text = tail.joinToString("\n")
        render()
    }

    private fun render() {
        val p = OmarchyRun.progress.snapshot
        bar.progress = p.percent
        pct.text = getString(R.string.ld_omarchy_pct, p.percent)
        stage.text = stageTitle(p)
        detail.text = p.detail
        val f = OmarchyRun.progress.lastFailure
        val running = s0().state != State.ERROR
        if (f != null && running && p.failed == null) {
            retrying.text = getString(R.string.ld_omarchy_retrying_from, f.index, stageTitleOf(f.index, f.total, f.title))
            retrying.visibility = View.VISIBLE
        } else {
            retrying.visibility = View.GONE
        }
    }

    private fun s0() = SessionBus.snapshot

    /** The stage line in the UI language: by phase and LINUXDESK_STAGE index; the script's English text otherwise. */
    private fun stageTitle(p: com.fire17.linuxdesk.linux.OmarchyProgress.Snapshot): String = when (p.phase) {
        Phase.STARTING -> getString(R.string.ld_omarchy_starting)
        Phase.DOWNLOAD -> getString(R.string.ld_omarchy_downloading)
        Phase.EXTRACT -> getString(R.string.ld_omarchy_extracting)
        Phase.BOOT -> getString(R.string.ld_omarchy_booting)
        Phase.UP -> getString(R.string.ld_omarchy_up)
        Phase.SETUP -> {
            val title = stageTitleOf(p.stageIndex, p.stageTotal, p.stage)
            if (p.stageIndex > 0) getString(R.string.ld_omarchy_stage_of, p.stageIndex, p.stageTotal, title) else title
        }
    }

    /** Stage [index]'s title in the UI language when it is one of setup-omarchy.sh's 7; else the script's [fallback]. */
    private fun stageTitleOf(index: Int, total: Int, fallback: String): String =
        STAGE_TITLES.getOrNull(index - 1)?.takeIf { total == STAGE_TITLES.size }?.let { getString(it) } ?: fallback

    private fun showError(s: SessionBus.Snapshot) {
        if (s.state != State.ERROR) { errorBox.visibility = View.GONE; return }
        // The most specific line wins: pacman's/the script's last error, else the service's own ERROR message.
        val p = OmarchyRun.progress.snapshot
        val e = p.error.ifEmpty { s.message.lineSequence().firstOrNull().orEmpty() }
        errorLine.text = e
        val f = p.failed ?: OmarchyRun.progress.lastFailure
        if (f != null) {
            errorTitle.text = getString(R.string.ld_omarchy_failed_at, f.index, f.total, stageTitleOf(f.index, f.total, f.title))
            retryBtn.text = getString(R.string.ld_omarchy_retry_from, f.index)
        } else {
            errorTitle.setText(R.string.ld_omarchy_failed)
            retryBtn.setText(R.string.ld_omarchy_retry)
        }
        errorBox.visibility = View.VISIBLE
    }

    private fun retry() {
        errorBox.visibility = View.GONE
        desktopOpened = false
        OmarchyRun.reset()
        OmarchyRun.setupShown = true
        render()
        SessionService.start(this, SessionService.ACTION_START)
    }

    private fun openDesktop() {
        desktopOpened = true
        OmarchyRun.desktopOpenedBySetup = true
        startActivity(Intent(this, DesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val TAIL_LINES = 12
        /** setup-omarchy.sh's 7 LINUXDESK_STAGE titles, in order (translated in strings_omarchy.xml). */
        private val STAGE_TITLES = intArrayOf(
            R.string.ld_omarchy_stage_1, R.string.ld_omarchy_stage_2, R.string.ld_omarchy_stage_3,
            R.string.ld_omarchy_stage_4, R.string.ld_omarchy_stage_5, R.string.ld_omarchy_stage_6,
            R.string.ld_omarchy_stage_7,
        )

        // Omarchy tokyo-night (themes/tokyo-night/colors.toml, v4.5.0)
        private val BG = Color.parseColor("#1a1b26")
        private val DARKER_BG = Color.parseColor("#0e0e14")
        private val LIGHTER_BG = Color.parseColor("#24283b")
        private val ERROR_BG = Color.parseColor("#2d1f2b")
        private val ACCENT = Color.parseColor("#7aa2f7")
        private val FG = Color.parseColor("#c0caf5")
        private val MUTED_FG = Color.parseColor("#565f89")
        private val GREEN = Color.parseColor("#9ece6a")
        private val RED = Color.parseColor("#f7768e")
        private val YELLOW = Color.parseColor("#e0af68")
        private val LOG_FG = Color.parseColor("#a9b1d6")
    }
}
