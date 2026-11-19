package com.fire17.linuxdesk.genie

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import com.fire17.linuxdesk.R
import java.util.Date

/**
 * The Pair screen (spec 01 §1.2, §4 rules 1, 2 and 5; spec 02 §03 overrides #10 and #12).
 *
 * Not paired: the 6-char code and "Share with my genie" (the line `LINUXDESK <node-id>:<CODE>`).
 * Paired: who and since when, and "Forget my genie" (the only way to unpair).
 * Always: the honesty text + the All-files caveat, and five switches, all OFF by default (spec 02 §03,
 * Tami override: defaults = Storage Genie's behaviour): Genie access; "Allow while the session runs"
 * (genie_window fgs, OFF = sg: on screen or within 2 min); Refuse while locked; the All-files XOR guard;
 * Developer: local link.
 */
class PairActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var who: TextView
    private lateinit var unpaired: View
    private lateinit var paired: View
    private lateinit var access: Switch
    private lateinit var windowFgs: Switch
    private lateinit var refuseLocked: Switch
    private lateinit var xorGuard: Switch
    private lateinit var wideGate: Switch
    private lateinit var localLink: Switch

    // Posted, never run inline: a switch's own listener triggers Binding.changed(), and re-binding that
    // switch from inside its listener would be re-entrant.
    private val onChange: () -> Unit = { status.post { if (!isFinishing) render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        status = findViewById(R.id.genie_status)
        code = findViewById(R.id.genie_code)
        who = findViewById(R.id.genie_who)
        unpaired = findViewById(R.id.genie_unpaired)
        paired = findViewById(R.id.genie_paired)
        access = findViewById(R.id.genie_access)
        windowFgs = findViewById(R.id.genie_window_fgs)
        refuseLocked = findViewById(R.id.genie_refuse_locked)
        xorGuard = findViewById(R.id.genie_xor_guard)
        wideGate = findViewById(R.id.genie_wide_gate)
        localLink = findViewById(R.id.genie_local_link)

        findViewById<Button>(R.id.genie_share).setOnClickListener { share() }
        findViewById<Button>(R.id.genie_forget).setOnClickListener { confirmForget() }
    }

    override fun onStart() {
        super.onStart()
        Binding.addListener(onChange)
    }

    override fun onResume() {
        super.onResume()
        // The 30-min clock starts when the code is on screen; a code older than that is replaced now.
        Binding.freshCodeIfStale(this)
        render()
    }

    override fun onStop() {
        Binding.removeListener(onChange)
        super.onStop()
    }

    private fun render() {
        val bound = Binding.isBound(this)
        unpaired.visibility = if (bound) View.GONE else View.VISIBLE
        paired.visibility = if (bound) View.VISIBLE else View.GONE
        if (bound) {
            val at = Binding.pairedAt(this)
            val name = Binding.genie(this).orEmpty().ifEmpty { "genie" }
            who.text = if (at > 0) getString(R.string.ld_genie_paired_with_since, name,
                DateFormat.getMediumDateFormat(this).format(Date(at)) + " " +
                    DateFormat.getTimeFormat(this).format(Date(at)))
            else getString(R.string.ld_genie_paired_with, name)
        } else {
            val c = Binding.code(this)
            code.text = c.chunked(3).joinToString(" ")
            code.contentDescription = c.toCharArray().joinToString(" ")
        }

        val on = GenieSettings.genieAccess(this)
        status.text = getString(when {
            !bound && !on -> R.string.ld_genie_status_unpaired_off
            !bound -> R.string.ld_genie_status_unpaired_on
            !on -> R.string.ld_genie_status_paired_off
            else -> R.string.ld_genie_status_paired_on
        })

        bindSwitch(access, on) { want ->
            if (GenieSettings.setGenieAccess(this, want)) true
            else { explainAllFilesConflict(); false }
        }
        bindSwitch(windowFgs, GenieSettings.genieWindow(this) == GenieSettings.Window.FGS) { want ->
            GenieSettings.setGenieWindow(this, if (want) GenieSettings.Window.FGS else GenieSettings.Window.SG); true
        }
        bindSwitch(refuseLocked, GenieSettings.refuseWhileLocked(this)) { want ->
            GenieSettings.setRefuseWhileLocked(this, want); true
        }
        bindSwitch(xorGuard, GenieSettings.xorGuard(this)) { want ->
            if (GenieSettings.setXorGuard(this, want)) explainGuardTurnedAccessOff()
            true
        }
        bindSwitch(wideGate, GenieSettings.wideWindowGate(this)) { want ->
            GenieSettings.setWideWindowGate(this, want); true
        }
        bindSwitch(localLink, GenieSettings.localLink(this)) { want ->
            GenieSettings.setLocalLink(this, want); true
        }
    }

    /** Sets a switch without firing its listener; [apply] returns false to bounce the switch back. */
    private fun bindSwitch(s: Switch, value: Boolean, apply: (Boolean) -> Boolean) {
        s.setOnCheckedChangeListener(null)
        s.isChecked = value
        s.setOnCheckedChangeListener { b: CompoundButton, want: Boolean ->
            if (!apply(want)) {
                b.setOnCheckedChangeListener(null)
                b.isChecked = !want
                render()
            }
        }
    }

    /** Only with the XOR guard ON (default OFF, spec 02 §03): All-files access and Genie access exclude each other. */
    private fun explainAllFilesConflict() {
        AlertDialog.Builder(this)
            .setTitle(R.string.ld_genie_xor_title)
            .setMessage(R.string.ld_genie_xor_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun explainGuardTurnedAccessOff() {
        AlertDialog.Builder(this)
            .setMessage(R.string.ld_genie_xor_turned_off)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setMessage(R.string.ld_genie_forget_q)
            .setPositiveButton(R.string.ld_genie_forget_yes) { _, _ -> Binding.forget(this); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun share() {
        // Shared as plain text, like Storage Genie. The line holds the public node id and the code only.
        val line = Binding.pairingLine(this)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, line),
            getString(R.string.ld_genie_share_title)))
    }
}
