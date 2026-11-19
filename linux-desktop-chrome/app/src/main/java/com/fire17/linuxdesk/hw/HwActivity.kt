package com.fire17.linuxdesk.hw

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fire17.linuxdesk.R
import org.json.JSONObject

/**
 * The Hardware screen (GPU/NPU plan v1 + cameras phase 0): a readable summary of HwProbe plus Copy.
 * Read-only. The probe runs on a background thread (EGL pbuffer + camera characteristics), never the UI thread.
 */
class HwActivity : Activity() {

    @Volatile private var json: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ld_hw_title)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val intro = TextView(this).apply { setText(R.string.ld_hw_intro) }
        val summary = TextView(this).apply {
            setText(R.string.ld_hw_probing); typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        val full = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true); visibility = View.GONE
        }
        val copy = Button(this).apply { setText(R.string.ld_hw_copy); isEnabled = false }
        val showJson = Button(this).apply { setText(R.string.ld_hw_show_json); isEnabled = false }
        copy.setOnClickListener {
            val j = json ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("LinuxDesk hardware", HwProbe.summary(j) + "\n" + j.toString(2)))
            Toast.makeText(this, R.string.ld_hw_copied, Toast.LENGTH_SHORT).show()
        }
        showJson.setOnClickListener {
            full.visibility = if (full.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(copy); addView(showJson) }
        col.addView(intro); col.addView(summary); col.addView(buttons); col.addView(full)
        setContentView(ScrollView(this).apply { addView(col) })

        Thread({
            val j = try { HwProbe.get(applicationContext) } catch (t: Throwable) {
                JSONObject().put("error", t.javaClass.simpleName)
            }
            json = j
            val text = try { HwProbe.summary(j) } catch (t: Throwable) { t.javaClass.simpleName }
            val pretty = j.toString(2)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                summary.text = text
                full.text = pretty
                copy.isEnabled = true
                showJson.isEnabled = true
            }
        }, "hw-probe").start()
    }
}
