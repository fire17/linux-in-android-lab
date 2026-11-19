package com.fire17.linuxdesk

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import com.termux.x11.LoriePreferences
import com.termux.x11.MainActivity

/**
 * The Linux display. It is upstream lorie's MainActivity, unchanged: its fullscreen LorieView (a
 * SurfaceView) receives the embedded X server's output through the ACTION_START binder handshake. Upstream
 * handles all input mapping here (Direct touch, Unicode IME commit, the Back key = toggle soft keyboard).
 *
 * LinuxDesk adds:
 *  - a small floating keyboard button (tap = show/hide the soft keyboard; long-press = session menu),
 *  - automatic keyboard show/hide requested by the focus-bridge extension (see FocusBridge),
 *  - a status line while Chromium starts,
 *  - system-gesture exclusion strips on both edges, so a horizontal swipe there reaches the page instead
 *    of triggering Android Back (Android allows at most 200dp per edge),
 *  - reporting of the view size and connection state to the session, which uses them for Chromium's
 *    window size,
 *  - the bottom key bar (x11/DesktopKeyBar: Super+Space, Super, Space, Esc, Tab, Ctrl, Alt, Shift, Enter,
 *    arrows), sent as real X key events through LorieView.sendKeyEvent; shown by default, hidden/shown from the
 *    long-press menu (remembered),
 *  - desktop gestures (x11/DesktopGestures: long-press = right click, 2-finger drag = scroll wheel, 3-finger
 *    horizontal swipe = i3 workspace), which see every touch before lorie; on by default, a long-press menu switch.
 */
class DesktopActivity : MainActivity(), SessionBus.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private var keyBar: com.fire17.linuxdesk.x11.DesktopKeyBar? = null
    private var gestures: com.fire17.linuxdesk.x11.DesktopGestures? = null
    private val poll = object : Runnable {
        override fun run() {
            val v = lorieView
            if (v != null) {
                SessionBus.desktopConnected = v.connected()
                if (v.width > 0 && v.height > 0) {
                    SessionBus.viewWidth = v.width
                    SessionBus.viewHeight = v.height
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (lorieView == null) return
        addOverlay()
        installGestures()
        installGestureExclusion()
        SessionBus.addListener(this)
        onState(SessionBus.snapshot)
        ui.post(poll)
    }

    override fun onPause() {
        keyBar?.keys?.releaseAll()   // never leave Super/Ctrl/... held down in X while the display is not shown
        super.onPause()
    }

    // Before lorie's own touch listener (on LorieView's parent): DesktopGestures replays to it what it does not take.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        gestures?.dispatch(ev) ?: super.dispatchTouchEvent(ev)

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        SessionBus.removeListener(this)
        SessionBus.desktopConnected = false
        super.onDestroy()
    }

    // ---- SessionBus.Listener -----------------------------------------------------------------

    override fun onState(s: SessionBus.Snapshot) {
        val st = status ?: return
        when (s.state) {
            SessionBus.State.RUNNING -> st.visibility = View.GONE
            SessionBus.State.STOPPED -> finish()
            else -> {
                st.visibility = View.VISIBLE
                st.text = s.message
            }
        }
    }

    override fun onKeyboardRequest(show: Boolean) {
        if (hasWindowFocus()) lorieView?.setKeyboardVisible(show)
    }

    // ---- overlay ------------------------------------------------------------------------------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun addOverlay() {
        val root = FrameLayout(this) // not clickable, so touches outside its children reach LorieView

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xCC202124.toInt()) }
            textSize = 13f
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })

        val kbd = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = getString(R.string.ld_toggle_keyboard)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99202124.toInt()) }
            alpha = 0.55f
            setOnClickListener { toggleKeyboardVisibility() }
            setOnLongClickListener { showMenu(it); true }
        }
        root.addView(kbd, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(8); bottomMargin = dp(96)   // stays above the bottom key bar (~50dp)
        })

        // Bottom key bar: Gboard has no Super key, and i3/Omarchy is driven by Super+… (Tami 2026-11-19).
        val bar = com.fire17.linuxdesk.x11.DesktopKeyBar(this) { code, down ->
            lorieView?.sendKeyEvent(0, code, down) ?: false
        }
        bar.visibility = if (keyBarOn()) View.VISIBLE else View.GONE
        keyBar = bar
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun installGestures() {
        val v = lorieView ?: return
        val g = com.fire17.linuxdesk.x11.DesktopGestures(v,
            mouse = { x, y, b, down, rel -> v.sendMouseEvent(x, y, b, down, rel) },
            wheel = { dx, dy -> v.sendMouseWheelEvent(dx, dy) },
            key = { code, down -> v.sendKeyEvent(0, code, down) },
            pass = { e -> super.dispatchTouchEvent(e) })
        g.enabled = gesturesOn()
        // Buttons over the display (keyboard button, lorie's extra keys) are skipped by DesktopGestures itself;
        // the key bar's modifiers are touch-listener views, not clickable ones, so it is named here.
        g.covered = { x, y -> hits(keyBar, x, y) }
        gestures = g
    }

    private fun hits(view: View?, x: Float, y: Float): Boolean {
        if (view == null || view.visibility != View.VISIBLE || !view.isAttachedToWindow) return false
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return x >= at[0] && y >= at[1] && x < at[0] + view.width && y < at[1] + view.height
    }

    private fun gesturesOn() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GESTURES, true)

    private fun keyBarOn() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_BAR, true)

    private fun setKeyBar(on: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_BAR, on).apply()
        keyBar?.let { if (!on) it.keys.releaseAll(); it.visibility = if (on) View.VISIBLE else View.GONE }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.ld_restart_browser)
        m.menu.add(0, 2, 1, R.string.ld_x_settings)
        m.menu.add(0, 3, 2, R.string.ld_back_to_launcher)
        m.menu.add(0, 4, 3, R.string.ld_stop)
        m.menu.add(0, 5, 4, if (keyBarOn()) R.string.ld_keybar_hide else R.string.ld_keybar_show)
        // Quick Sound toggle (ld-perf, Tami 21:36): checkable item showing the current state; live, no restart.
        m.menu.add(0, 6, 5, R.string.ld_sound_on).apply {
            isCheckable = true
            isChecked = com.fire17.linuxdesk.audio.SoundSwitch.isOn(this@DesktopActivity)
        }
        m.menu.add(0, 7, 6, R.string.ld_gestures).apply { isCheckable = true; isChecked = gesturesOn() }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> SessionService.start(this, SessionService.ACTION_RESTART_BROWSER)
                2 -> startActivity(Intent(this, LoriePreferences::class.java).setAction(Intent.ACTION_MAIN))
                3 -> startActivity(Intent(this, LauncherActivity::class.java).putExtra(LauncherActivity.EXTRA_STAY, true))
                4 -> SessionService.start(this, SessionService.ACTION_STOP)
                5 -> setKeyBar(!keyBarOn())
                6 -> {
                    val on = com.fire17.linuxdesk.audio.SoundSwitch.toggle(this)
                    android.widget.Toast.makeText(this, if (on) R.string.ld_sound_on else R.string.ld_sound_off,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                7 -> {
                    val on = !gesturesOn()
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_GESTURES, on).apply()
                    gestures?.enabled = on
                    android.widget.Toast.makeText(this, if (on) R.string.ld_gestures_on else R.string.ld_gestures_off,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        m.show()
    }

    private fun installGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val v = lorieView ?: return
        v.addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            val strip = dp(24)
            val half = dp(100) // 200dp total per edge, which is Android's limit
            val cy = h / 2
            view.systemGestureExclusionRects = listOf(
                Rect(0, cy - half, strip, cy + half),
                Rect(w - strip, cy - half, w, cy + half),
            )
        }
    }

    private companion object {
        const val PREFS = "linuxdesk_desktop"
        const val KEY_BAR = "keyBar"
        const val KEY_GESTURES = "desktopGestures"
    }
}
