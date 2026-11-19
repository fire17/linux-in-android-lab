package com.fire17.linuxinapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import com.fire17.linuxinapp.proot.ProotLauncher
import com.fire17.linuxinapp.rootfs.RootfsInstaller
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlin.concurrent.thread

/**
 * Plain-View activity (no Compose/AppCompat: keeps the APK small and the dependency surface to
 * just the Termux terminal library).
 *
 * Launch flow: start+bind LinuxService → extract rootfs on a worker thread if missing →
 * attach the service-owned proot TerminalSession to the TerminalView.
 */
class MainActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    private var service: LinuxService? = null
    private var rootfsReady = false
    private var ctrlLatched = false
    private var fontSizePx = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LinuxService.LocalBinder).service
            service?.clientDelegate = sessionClient
            maybeAttachSession()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // targetSdk 35+ enforces edge-to-edge, and adjustResize alone no longer guarantees that the
        // soft keyboard pushes the terminal up. Pad the root by system bars + IME explicitly so the
        // prompt and the extra-keys row stay visible above the keyboard.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            // Edge-to-edge is only enforced on API 35+ (for targetSdk 35+); below that, the legacy
            // fitsSystemWindows + adjustResize path already works, so keep it to avoid double padding.
            if (Build.VERSION.SDK_INT >= 35) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            } else {
                v.onApplyWindowInsets(insets) // legacy fitsSystemWindows path (API 26-34)
            }
        }
        terminalView = findViewById(R.id.terminal_view)
        status = findViewById(R.id.status)

        fontSizePx = (14 * resources.displayMetrics.scaledDensity).toInt()
        terminalView.setTerminalViewClient(viewClient)
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true

        findViewById<Button>(R.id.btn_selftest).setOnClickListener { runSelfTest() }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { restartShell() }
        findViewById<Button>(R.id.btn_keyboard).setOnClickListener { showKeyboard() }
        findViewById<Button>(R.id.key_esc).setOnClickListener { sendRaw("\u001b") }
        findViewById<Button>(R.id.key_tab).setOnClickListener { sendRaw("\t") }
        findViewById<Button>(R.id.key_ctrl).setOnClickListener { v -> ctrlLatched = !ctrlLatched; v.isSelected = ctrlLatched; v.alpha = if (ctrlLatched) 1f else 0.6f }
        findViewById<Button>(R.id.key_up).setOnClickListener { sendRaw("\u001b[A") }
        findViewById<Button>(R.id.key_down).setOnClickListener { sendRaw("\u001b[B") }
        findViewById<Button>(R.id.key_left).setOnClickListener { sendRaw("\u001b[D") }
        findViewById<Button>(R.id.key_right).setOnClickListener { sendRaw("\u001b[C") }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val svc = Intent(this, LinuxService::class.java)
        startForegroundService(svc)
        bindService(svc, conn, Context.BIND_AUTO_CREATE)

        prepareRootfs()
    }

    override fun onDestroy() {
        service?.clientDelegate = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ boot sequence

    private fun prepareRootfs() {
        val installer = RootfsInstaller(this)
        if (installer.isInstalled()) { rootfsReady = true; setStatus(getString(R.string.status_ready)); return }
        setStatus(getString(R.string.status_extracting))
        thread(name = "rootfs-extract") {
            val msg = try {
                installer.ensureInstalled { p -> main.post { setStatus(getString(R.string.status_extracting) + " " + p) } }
            } catch (t: Throwable) {
                Log.e(TAG, "rootfs install failed", t)
                main.post { setStatus("rootfs install FAILED: ${t.message}") }
                return@thread
            }
            Log.i(TAG, msg)
            main.post {
                rootfsReady = true
                setStatus(getString(R.string.status_ready))
                maybeAttachSession()
            }
        }
    }

    private fun maybeAttachSession() {
        val svc = service ?: return
        if (!rootfsReady) return
        val s = svc.getOrCreateSession()
        if (terminalView.currentSession !== s) terminalView.attachSession(s)
        terminalView.requestFocus()
        // The proot process is spawned by TerminalSession.initializeEmulator() on first layout.
    }

    private fun restartShell() {
        val svc = service ?: return
        if (!rootfsReady) return
        val s = svc.restartSession()
        terminalView.attachSession(s)
        terminalView.requestFocus()
    }

    // ------------------------------------------------------------------ self-test

    // Context.getSystemService(Class) is @Nullable in the SDK stubs: safe calls keep this compiling
    // whether kotlinc sees it as a platform type or as strictly nullable.

    private fun runSelfTest() {
        if (!rootfsReady) { setStatus(getString(R.string.status_extracting)); return }
        setStatus(getString(R.string.status_selftest_running))
        thread(name = "selftest") {
            val launcher = ProotLauncher(this)
            val pre = launcher.preflight().joinToString("\n")
            val text = try {
                // One proot run per step, so e.g. a fork failure can't hide whether exec works.
                val steps = launcher.runSelfTestSteps(timeoutSec = 60)
                val out = steps.associate { it.name to it.result.output }
                val verdict = if (steps.all { it.ok } &&
                    out["os-release"].orEmpty().contains("Alpine", ignoreCase = true) &&
                    out["id"].orEmpty().contains("uid=0")) "PASS" else "FAIL"
                val totalMs = steps.sumOf { it.result.millis }
                val summary = steps.joinToString("\n") { s ->
                    "[${if (s.ok) "ok" else "FAIL"}] ${s.name} (exit=${s.result.exitCode}, ${s.result.millis} ms) :: ${s.firstLine}"
                }
                val details = steps.joinToString("\n") { "--- ${it.name}: ${it.script}\n${it.result.output.trimEnd()}" }
                "SELF-TEST $verdict  (${steps.count { it.ok }}/${steps.size} steps, $totalMs ms, abi=${Build.SUPPORTED_ABIS.first()})\n\n" +
                    "$summary\n\n$details\n--- preflight ---\n$pre\n\n--- argv ---\n${steps.first().result.command.joinToString(" ")}"
            } catch (t: Throwable) {
                "SELF-TEST FAIL: ${t.javaClass.simpleName}: ${t.message}\n\n--- preflight ---\n$pre"
            }
            Log.i(SELFTEST_TAG, text)
            try { File(filesDir, "selftest.txt").writeText(text) } catch (_: Exception) {}
            main.post {
                setStatus(text.lineSequence().first())
                AlertDialog.Builder(this)
                    .setTitle(R.string.selftest_title)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.copy) { _, _ ->
                        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("selftest", text))
                    }
                    .show()
                    .findViewById<TextView>(android.R.id.message)?.apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun setStatus(s: String) { status.text = s }

    private fun sendRaw(s: String) {
        terminalView.currentSession?.write(s)
        terminalView.requestFocus()
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    // ------------------------------------------------------------------ TerminalViewClient
    // Separate objects (not `this`): Activity.onKeyUp(int, KeyEvent) has the same signature as
    // TerminalViewClient.onKeyUp and overriding both would break the Back key.

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val inc = if (scale > 1f) 2 else -2
                val min = (8 * resources.displayMetrics.scaledDensity).toInt()
                val max = (32 * resources.displayMetrics.scaledDensity).toInt()
                fontSizePx = (fontSizePx + inc).coerceIn(min, max)
                terminalView.setTextSize(fontSizePx)
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean {
            val v = ctrlLatched
            if (v) main.post {
                ctrlLatched = false
                findViewById<View>(R.id.key_ctrl)?.apply { isSelected = false; alpha = 0.6f }
            }
            return v
        }
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    // ------------------------------------------------------------------ TerminalSessionClient (delegated from LinuxService)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            setStatus(getString(R.string.status_finished, finishedSession.exitStatus))
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: return
            terminalView.mEmulator?.paste(text)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { terminalView.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null // library default (block)
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    companion object {
        private const val TAG = "LinuxInApp"
        const val SELFTEST_TAG = "LinuxInAppSelfTest"
    }
}
