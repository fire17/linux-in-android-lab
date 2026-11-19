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
 *    window size.
 */
class DesktopActivity : MainActivity(), SessionBus.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private var status: TextView? = null
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
        installGestureExclusion()
        SessionBus.addListener(this)
        onState(SessionBus.snapshot)
        ui.post(poll)
    }

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
            rightMargin = dp(8); bottomMargin = dp(96)
        })

        addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.ld_restart_browser)
        m.menu.add(0, 2, 1, R.string.ld_x_settings)
        m.menu.add(0, 3, 2, R.string.ld_back_to_launcher)
        m.menu.add(0, 4, 3, R.string.ld_stop)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> SessionService.start(this, SessionService.ACTION_RESTART_BROWSER)
                2 -> startActivity(Intent(this, LoriePreferences::class.java).setAction(Intent.ACTION_MAIN))
                3 -> startActivity(Intent(this, LauncherActivity::class.java).putExtra(LauncherActivity.EXTRA_STAY, true))
                4 -> SessionService.start(this, SessionService.ACTION_STOP)
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
}
