package com.fire17.linuxdesk.ota

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.fire17.linuxdesk.BuildConfig
import com.fire17.linuxdesk.R

/** OTA settings, in LinuxDesk's own prefs file (keys prefixed ota_). Auto-apply is OFF by default (Tami decides). */
class OtaSettings(context: Context) {
    private val p = context.getSharedPreferences("linuxdesk_settings", Context.MODE_PRIVATE)

    var autoApply: Boolean
        get() = p.getBoolean("ota_auto_apply", false)
        set(v) = p.edit().putBoolean("ota_auto_apply", v).apply()

    /** Base URL holding manifest.json + bundle.zip. Integrity comes from the signature, never from the host. */
    var baseUrl: String
        get() = p.getString("ota_base_url", DEFAULT_BASE).let { if (it == null || it == OLD_DEFAULT_BASE) DEFAULT_BASE else it }
        set(v) = p.edit().putString("ota_base_url", v).apply()

    companion object {
        // A fixed tag, not GitHub's "latest": the rootfs releases (omarchy-rootfs-v1, ...) became "latest" on
        // 2026-11-20 and the check answered HTTP_404 (Tami's 12:07 video). OTA bundles go to the ota-latest release.
        const val DEFAULT_BASE = "https://github.com/fire17/linux-in-android-lab/releases/download/ota-latest/"
        const val OLD_DEFAULT_BASE = "https://github.com/fire17/linux-in-android-lab/releases/latest/download/"
    }
}

/**
 * The Updates screen (OTA v1): Check / Install / Later, the auto-apply switch (default OFF) and the ledger.
 * Network work runs on a background thread. Install stages + activates; the bundle takes effect at the next session
 * start (RootfsInstaller.installHelpers overlays ota/active), on trial until the health check passes.
 */
class UpdatesActivity : Activity() {

    private lateinit var store: BundleStore
    private lateinit var settings: OtaSettings
    private lateinit var status: TextView
    private lateinit var ledger: TextView
    private lateinit var installBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ld_ota_title)
        store = BundleStore(filesDir, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
        settings = OtaSettings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        status = TextView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val checkBtn = Button(this).apply { setText(R.string.ld_ota_check); setOnClickListener { check() } }
        installBtn = Button(this).apply { setText(R.string.ld_ota_install); isEnabled = false; setOnClickListener { install() } }
        val laterBtn = Button(this).apply { setText(R.string.ld_ota_later); setOnClickListener { finish() } }
        row.addView(checkBtn); row.addView(installBtn); row.addView(laterBtn)

        val auto = Switch(this).apply {
            setText(R.string.ld_ota_auto_apply)
            isChecked = settings.autoApply
            setOnCheckedChangeListener { _, on -> settings.autoApply = on }
        }
        val ledgerTitle = TextView(this).apply { setText(R.string.ld_ota_ledger); setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) }
        ledger = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }

        col.addView(status); col.addView(row); col.addView(auto); col.addView(ledgerTitle); col.addView(ledger)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun refresh(msg: String? = null) {
        val s = store.state()
        val base = getString(R.string.ld_ota_status_fmt, BuildConfig.VERSION_NAME, s.optLong("active", 0),
            s.optLong("prev", 0), if (s.optBoolean("trial")) getString(R.string.ld_ota_trial) else "")
        status.text = if (msg != null) "$base\n$msg" else base
        ledger.text = store.ledgerRows(30).joinToString("\n") { r ->
            val to = r.optJSONObject("to")?.optLong("bundle")?.let { " → b$it" } ?: ""
            val why = r.optString("reason", r.optString("rollback_reason", ""))
            "${r.optString("ts").take(19)}  ${r.optString("event")}$to ${why}".trimEnd()
        }.ifEmpty { getString(R.string.ld_ota_ledger_empty) }
    }

    private fun bg(work: () -> String) {
        status.text = getString(R.string.ld_ota_working)
        Thread {
            val m = try { work() } catch (e: Exception) { getString(R.string.ld_ota_failed, e.message ?: "") }
            runOnUiThread { refresh(m) }
        }.start()
    }

    private fun check() = bg {
        when (val r = store.checkRemote(settings.baseUrl + "manifest.json")) {
            is BundleVerifier.Result.Ok -> {
                runOnUiThread { installBtn.isEnabled = true }
                getString(R.string.ld_ota_available, r.manifest.version, r.manifest.files.size)
            }
            is BundleVerifier.Result.Rejected ->
                if (r.reason == "SAME_VERSION" || r.reason == "DOWNGRADE") getString(R.string.ld_ota_up_to_date)
                else getString(R.string.ld_ota_failed, r.reason)
        }
    }

    private fun install() = bg {
        store.downloadAndStage(settings.baseUrl + "manifest.json", settings.baseUrl + "bundle.zip")
        val v = store.activateStaged(consentMode = "tap")
        runOnUiThread { installBtn.isEnabled = false }
        getString(R.string.ld_ota_installed, v)
    }
}
