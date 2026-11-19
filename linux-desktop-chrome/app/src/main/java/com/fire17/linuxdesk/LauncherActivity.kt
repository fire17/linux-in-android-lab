package com.fire17.linuxdesk

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fire17.linuxdesk.SessionBus.State
import com.fire17.linuxdesk.audio.MicIn
import com.fire17.linuxdesk.bridge.ChromiumFlags
import com.fire17.linuxdesk.genie.PairActivity
import com.fire17.linuxdesk.linux.Distro
import com.fire17.linuxdesk.storage.ReviewActivity

/**
 * The setup and status screen. Start → SessionService does the work → as soon as the X server is coming
 * up, the app switches to DesktopActivity (the Linux display). Comes back here on errors, or when the
 * user long-presses the keyboard button and picks "Back to launcher".
 */
class LauncherActivity : Activity(), SessionBus.Listener {

    private lateinit var settings: LinuxDeskSettings
    private lateinit var stateText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var startBtn: Button
    private lateinit var openBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var rootfsProgress: ProgressBar
    private lateinit var distroGroup: RadioGroup
    private var autoOpened = false
    private lateinit var micSwitch: Switch
    private var micSyncing = false

    companion object {
        /** Set by DesktopActivity's "Back to launcher": stay on this screen, don't bounce back to the desktop. */
        const val EXTRA_STAY = "com.fire17.linuxdesk.extra.STAY"
        private const val REQ_MIC = 2
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STAY, false)) autoOpened = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        settings = LinuxDeskSettings(this)
        if (intent.getBooleanExtra(EXTRA_STAY, false)) autoOpened = true

        stateText = findViewById(R.id.state)
        logText = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)
        startBtn = findViewById(R.id.btn_start)
        openBtn = findViewById(R.id.btn_open)
        stopBtn = findViewById(R.id.btn_stop)

        rootfsProgress = findViewById(R.id.rootfs_progress)
        distroGroup = findViewById(R.id.distro)
        // Alpine is the default and the only choice on x86_64 (Arch Linux ARM is aarch64 only).
        findViewById<View>(R.id.distro_omarchy).isEnabled = Distro.omarchySupported()
        distroGroup.check(if (Distro.current(this) == Distro.OMARCHY) R.id.distro_omarchy else R.id.distro_alpine)
        distroGroup.setOnCheckedChangeListener { _, id ->
            Distro.set(this, if (id == R.id.distro_omarchy) Distro.OMARCHY else Distro.ALPINE)
        }

        val url = findViewById<EditText>(R.id.url)
        url.setText(settings.homeUrl)

        val modes = findViewById<RadioGroup>(R.id.mode)
        modes.check(
            when (settings.mode) {
                ChromiumFlags.Mode.BROWSER -> R.id.mode_browser
                ChromiumFlags.Mode.APP -> R.id.mode_app
                ChromiumFlags.Mode.KIOSK -> R.id.mode_kiosk
            }
        )
        modes.setOnCheckedChangeListener { _, id ->
            settings.mode = when (id) {
                R.id.mode_app -> ChromiumFlags.Mode.APP
                R.id.mode_kiosk -> ChromiumFlags.Mode.KIOSK
                else -> ChromiumFlags.Mode.BROWSER
            }
        }

        val scaleLabel = findViewById<TextView>(R.id.scale_label)
        val scale = findViewById<SeekBar>(R.id.scale)
        fun showScale(pct: Int) {
            val f = ChromiumFlags.scaleFactor(resources.displayMetrics.density, pct)
            scaleLabel.text = getString(R.string.ld_scale_label, pct, ChromiumFlags.formatScale(f))
        }
        // The SeekBar range is 0..150 and maps to 50..200 %.
        scale.progress = (settings.scalePercent - 50).coerceIn(0, 150)
        showScale(settings.scalePercent)
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                settings.scalePercent = p + 50
                showScale(p + 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        bindCheck(R.id.opt_auto_kbd, settings.autoKeyboard) { settings.autoKeyboard = it }
        bindCheck(R.id.opt_legacy, settings.legacyDrawing) { settings.legacyDrawing = it }
        bindCheck(R.id.opt_noseccomp, settings.prootNoSeccomp) { settings.prootNoSeccomp = it }
        bindCheck(R.id.opt_gpu, settings.useGpu) { settings.useGpu = it }
        bindCheck(R.id.opt_sound, settings.sound) { settings.sound = it }

        // Microphone to Linux: the ONLY place consent can become true. RECORD_AUDIO is asked for only here, only
        // when the person turns it ON. Deliberately no autostart/intent extra can flip it.
        micSwitch = findViewById(R.id.opt_mic)
        micSwitch.isChecked = MicIn.consent
        micSwitch.setOnCheckedChangeListener { _, on ->
            if (micSyncing) return@setOnCheckedChangeListener
            if (!on) { setMic(false); return@setOnCheckedChangeListener }
            if (MicIn.hasPermission(this)) setMic(true)
            else { syncMicSwitch(false); requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
        }

        startBtn.setOnClickListener {
            settings.homeUrl = ChromiumFlags.normalizeUrl(url.text.toString())
            autoOpened = false
            ensureNotificationPermission()
            SessionService.start(this, SessionService.ACTION_START)
        }
        openBtn.setOnClickListener { openDesktop() }
        stopBtn.setOnClickListener { SessionService.start(this, SessionService.ACTION_STOP) }
        findViewById<Button>(R.id.btn_restart_browser).setOnClickListener {
            settings.homeUrl = ChromiumFlags.normalizeUrl(url.text.toString())
            SessionService.start(this, SessionService.ACTION_RESTART_BROWSER)
        }
        findViewById<Button>(R.id.btn_genie).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.btn_storage).setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        findViewById<Button>(R.id.btn_dev_options).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.ld_dev_options_missing, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btn_share_logs).setOnClickListener {
            if (!LogShare.share(this)) Toast.makeText(this, R.string.ld_share_logs_failed, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("LinuxDesk log", SessionBus.logText()))
            Toast.makeText(this, R.string.ld_copied, Toast.LENGTH_SHORT).show()
        }

        // Headless start for adb and the CI emulator job:
        //   adb shell am start -n com.fire17.linuxdesk/.LauncherActivity --ez autostart true [--es url https://…]
        intent.getStringExtra("url")?.let { url.setText(it) }
        if (savedInstanceState == null && intent.getBooleanExtra("autostart", false)) startBtn.performClick()
    }

    private fun setMic(on: Boolean) {
        if (on) MicIn.setConsent(true) else MicIn.setConsent(false)   // false also stops capture right now
        val s = SessionBus.snapshot.state
        if (s !in setOf(State.IDLE, State.STOPPED, State.ERROR)) {
            SessionService.start(this, if (on) SessionService.ACTION_MIC_APPLY else SessionService.ACTION_MIC_OFF)
        } else if (on) {
            Toast.makeText(this, "The microphone will turn on when the session starts", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncMicSwitch(on: Boolean) {
        micSyncing = true
        micSwitch.isChecked = on
        micSyncing = false
    }

    override fun onMicChanged() = syncMicSwitch(MicIn.consent)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            syncMicSwitch(true); setMic(true)
        } else {
            syncMicSwitch(false)
            Toast.makeText(this, R.string.ld_mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun bindCheck(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        val cb = findViewById<CheckBox>(id)
        cb.isChecked = value
        cb.setOnCheckedChangeListener { _, b -> set(b) }
    }

    override fun onStart() {
        super.onStart()
        SessionBus.addListener(this)
        logText.text = SessionBus.logText()
        onState(SessionBus.snapshot)
        syncMicSwitch(MicIn.consent)
    }

    override fun onStop() {
        SessionBus.removeListener(this)
        super.onStop()
    }

    override fun onState(s: SessionBus.Snapshot) {
        // The distro is fixed while a session runs (each has its own rootfs; switch after Stop).
        val idle = s.state in setOf(State.IDLE, State.STOPPED, State.ERROR)
        for (i in 0 until distroGroup.childCount) {
            val c = distroGroup.getChildAt(i)
            c.isEnabled = idle && (c.id != R.id.distro_omarchy || Distro.omarchySupported())
        }
        val pct = SessionBus.downloadPercent
        rootfsProgress.visibility = if (s.state == State.INSTALLING_ROOTFS && pct in 0..100) View.VISIBLE else View.GONE
        if (pct in 0..100) rootfsProgress.progress = pct
        stateText.text = getString(R.string.ld_state_fmt, s.state.name, s.message)
        val active = s.state !in setOf(State.IDLE, State.STOPPED, State.ERROR)
        startBtn.isEnabled = !active
        stopBtn.isEnabled = active && s.state != State.STOPPING
        val xUp = s.state in setOf(State.STARTING_X, State.WAITING_FOR_DISPLAY, State.STARTING_BROWSER,
            State.RUNNING, State.BROWSER_EXITED)
        openBtn.visibility = if (xUp) View.VISIBLE else View.GONE
        // Switch to the display as soon as the X server is starting. LorieView keeps retrying the connection.
        if (xUp && !autoOpened && s.state != State.BROWSER_EXITED) {
            autoOpened = true
            openDesktop()
        }
    }

    override fun onLog(line: String) {
        logText.append(line)
        logText.append("\n")
        if (logText.lineCount > 400) logText.text = SessionBus.logText().takeLast(20_000)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openDesktop() {
        startActivity(Intent(this, DesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
