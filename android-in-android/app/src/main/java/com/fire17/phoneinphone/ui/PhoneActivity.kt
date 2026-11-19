package com.fire17.phoneinphone.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fire17.phoneinphone.R
import com.fire17.phoneinphone.backend.BackendFactory
import com.fire17.phoneinphone.backend.BackendListener
import com.fire17.phoneinphone.backend.PhoneBackend
import com.fire17.phoneinphone.backend.Settings
import com.fire17.phoneinphone.backend.Stage
import com.fire17.phoneinphone.backend.StageStatus

/**
 * The phone-in-a-phone screen: guest surface (or the experiment's stage ladder), a nav bar
 * (Back / Home / Recents / Keyboard), a stats HUD, and a live log.
 */
class PhoneActivity : Activity(), BackendListener, SurfaceHolder.Callback {

    private lateinit var backend: PhoneBackend
    private lateinit var frame: AspectFrameLayout
    private lateinit var surfaceView: GuestSurfaceView
    private lateinit var stagesView: LinearLayout
    private lateinit var tvHud: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private val stageRows = LinkedHashMap<String, TextView>()
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_phone)
        findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0).padForSystemBars()

        frame = findViewById(R.id.guestFrame)
        surfaceView = findViewById(R.id.guestSurface)
        stagesView = findViewById(R.id.stages)
        tvHud = findViewById(R.id.tvHud)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        val settings = Settings.load(Settings.prefs(this))
        backend = BackendFactory.create(this, settings)
        title = backend.displayName

        surfaceView.backend = backend
        surfaceView.holder.addCallback(this)
        if (!backend.rendersVideo) {
            // OnDevice: show the ladder full-size, no surface
            frame.visibility = View.GONE
            findViewById<View>(R.id.navBar).visibility = View.GONE
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { tapKey(KeyEvent.KEYCODE_BACK) }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tapKey(KeyEvent.KEYCODE_HOME) }
        findViewById<Button>(R.id.btnRecents).setOnClickListener { tapKey(KeyEvent.KEYCODE_APP_SWITCH) }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            surfaceView.requestFocus()
            // getSystemService(Class) is @Nullable in the SDK stubs: safe call keeps it compiling either way.
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(surfaceView, InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        backend.start(this)
        ui.post(hudTick)
    }

    private fun tapKey(code: Int) {
        backend.sendKey(KeyEvent.ACTION_DOWN, code)
        backend.sendKey(KeyEvent.ACTION_UP, code)
    }

    private val hudTick = object : Runnable {
        override fun run() {
            val s = backend.stats()
            tvHud.text = if (backend.rendersVideo)
                "%s  %dx%d  %.0f fps  %.0f kbps  frames %d".format(s.extra, s.videoWidth, s.videoHeight, s.fps, s.kbps, s.framesDecoded)
            else s.extra
            ui.postDelayed(this, 1000)
        }
    }

    // ---- SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) = backend.setSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = backend.setSurface(null)

    // ---- BackendListener (called from worker threads)
    override fun onStage(stage: Stage) = ui.post {
        val row = stageRows.getOrPut(stage.key) {
            TextView(this).apply {
                textSize = 13f
                setPadding(8, 6, 8, 6)
                setTextIsSelectable(true)
                stagesView.addView(this)
            }
        }
        row.text = "${stage.status.mark}  ${stage.title}" + if (stage.detail.isNotBlank()) "\n     ${stage.detail}" else ""
        row.setTextColor(
            when (stage.status) {
                StageStatus.OK -> 0xFF2E7D32.toInt()
                StageStatus.FAIL -> 0xFFC62828.toInt()
                StageStatus.RUNNING -> 0xFF1565C0.toInt()
                StageStatus.NOT_IMPLEMENTED -> 0xFF6A1B9A.toInt()
                else -> 0xFF616161.toInt()
            },
        )
    }.let { }

    override fun onLog(line: String) = ui.post {
        logLines.addLast(line)
        while (logLines.size > 400) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }.let { }

    override fun onVideoSize(width: Int, height: Int) = ui.post {
        frame.setAspect(width, height)
        // hide the stage ladder once video is flowing, to give the guest the full frame
        stagesView.visibility = View.GONE
    }.let { }

    override fun onStopped(error: Throwable?) = ui.post {
        stagesView.visibility = View.VISIBLE
        val tv = TextView(this).apply {
            textSize = 14f
            setPadding(8, 12, 8, 12)
            text = if (error == null) "■ stopped" else "■ stopped: ${error.javaClass.simpleName}: ${error.message}"
        }
        stagesView.addView(tv)
    }.let { }

    override fun onDestroy() {
        ui.removeCallbacks(hudTick)
        backend.stop()
        super.onDestroy()
    }
}
