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
import android.widget.ScrollView
import android.widget.TextView
import com.fire17.linuxdesk.SessionBus.State
import com.fire17.linuxdesk.linux.OmarchyRun

/**
 * Full-screen "Omarchy setup" screen (Tami AC21A217: the install and the boot must be VISIBLE). Shows the
 * whole first run live: the Arch Linux ARM download, the unpack, every setup-omarchy.sh stage with pacman's
 * package-by-package progress, then the boot (X server, i3, Chrome). A big progress bar on top
 * (OmarchyProgress: download 0–35, unpack 35–45, setup 45–95, boot 95–100), the current stage and package in
 * big text, and the live log tail below (the same lines as the launcher's session log).
 *
 * Opened by the launcher when Start is pressed with the Omarchy distro. It switches to the Linux display
 * (DesktopActivity) by itself as soon as the X server is coming up, so the boot is watched on the real desktop:
 * the i3 bar, wallpaper and theme appear there. Built in code (no layout/strings files: those belong to
 * other lanes). Colors are Omarchy's tokyo-night.
 */
class OmarchySetupActivity : Activity(), SessionBus.Listener {

    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var stage: TextView
    private lateinit var detail: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var state: TextView
    private var desktopOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Omarchy"
            setTextColor(ACCENT); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Arch Linux ARM + Omarchy's look, inside LinuxDesk"
            setTextColor(MUTED_FG); textSize = 14f
        })
        pct = TextView(this).apply {
            setTextColor(FG); textSize = 48f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(pct)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(LIGHTER_BG)
            minimumHeight = dp(18)
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
        stage = TextView(this).apply {
            setTextColor(FG); textSize = 20f; setPadding(0, dp(16), 0, 0)
        }
        root.addView(stage)
        detail = TextView(this).apply {
            setTextColor(GREEN); textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, 0)
        }
        root.addView(detail)
        state = TextView(this).apply {
            setTextColor(MUTED_FG); textSize = 12f; setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(state)

        log = TextView(this).apply {
            setTextColor(LOG_FG); textSize = 10.5f; typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logScroll = ScrollView(this).apply { setBackgroundColor(DARKER_BG); addView(log) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(Button(this).apply {
            text = "Open desktop"
            setOnClickListener { openDesktop() }
        })
        buttons.addView(Button(this).apply {
            text = "Back"
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
        log.text = SessionBus.logText().takeLast(12_000)
        render()
        onState(SessionBus.snapshot)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onStop() {
        SessionBus.removeListener(this)
        super.onStop()
    }

    override fun onState(s: SessionBus.Snapshot) {
        state.text = "${s.state.name}: ${s.message}"
        render()
        when (s.state) {
            // The boot is shown on the real Linux display: i3, its bar, the wallpaper and the theme come up there.
            State.STARTING_X, State.WAITING_FOR_DISPLAY, State.STARTING_BROWSER, State.RUNNING ->
                if (!desktopOpened) openDesktop()
            State.ERROR -> stage.text = "Setup stopped: see the log below. Fix the network, then Start again."
            else -> {}
        }
    }

    override fun onLog(line: String) {
        log.append(line); log.append("\n")
        if (log.length() > 40_000) log.text = SessionBus.logText().takeLast(12_000)
        render()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun render() {
        val p = OmarchyRun.progress.snapshot
        bar.progress = p.percent
        pct.text = "${p.percent} %"
        stage.text = p.stage
        detail.text = p.detail
    }

    private fun openDesktop() {
        desktopOpened = true
        OmarchyRun.desktopOpenedBySetup = true
        startActivity(Intent(this, DesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        // Omarchy tokyo-night (themes/tokyo-night/colors.toml, v4.5.0)
        private val BG = Color.parseColor("#1a1b26")
        private val DARKER_BG = Color.parseColor("#0e0e14")
        private val LIGHTER_BG = Color.parseColor("#24283b")
        private val ACCENT = Color.parseColor("#7aa2f7")
        private val FG = Color.parseColor("#c0caf5")
        private val MUTED_FG = Color.parseColor("#565f89")
        private val GREEN = Color.parseColor("#9ece6a")
        private val LOG_FG = Color.parseColor("#a9b1d6")
    }
}
