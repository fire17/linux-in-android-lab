package com.fire17.linuxdesk.storage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fire17.linuxdesk.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Storage screen + THE review sheet (spec 01 §1.3 "Delete rule": deletes are in-app-confirm only).
 *
 *  - pending: the genie's suggestion, every item UNticked (SG rule); Continue freezes the ticks (selected),
 *    Trash / Delete act directly but still go through this same sheet; Keep everything dismisses.
 *  - selected: the frozen list, waiting for the genie's fs.execute; Make changes / Cancel.
 *  - awaiting_confirm: the review sheet. Every file, its type and size, the by-type total, permanent vs Trash.
 *    Approve -> Proposals.approve (re-check) -> MediaStore system dialog for media -> Proposals.complete.
 *    Make changes -> pending. Cancel / back -> selected (dialog_cancelled). Nothing outside this Activity
 *    can approve anything: it is not exported, and no verb or intent extra skips the Approve tap.
 *  - always: storage access (All-files access, picked folders) and the deletion log.
 *
 * Strings: v1 keeps this screen's text in [S] (English + Hebrew by locale) because res/values*\/strings*.xml
 * are owned by other lanes; the integrator may move them into strings_storage.xml.
 */
class ReviewActivity : Activity() {
    companion object {
        const val EXTRA_ID = "ld.storage.proposal"
        const val EXTRA_CONFIRM = "ld.storage.confirm"
        private const val REQ_EXEC = 41
        private const val REQ_TREE = 42
        private const val REQ_ALLFILES = 43
        private const val LOG_ROWS = 50
    }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var banner: TextView
    private lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout
    private val checks = LinkedHashSet<String>()
    private var shownId: String? = null
    private var dialogInFlight = false
    private var answered = false        // one decision per sheet showing
    private var showingLog = false
    private var sheetKey: String? = null   // id|requested_at of the sheet on screen: a new request re-arms the buttons

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        title = findViewById(R.id.review_title)
        subtitle = findViewById(R.id.review_subtitle)
        banner = findViewById(R.id.review_banner)
        content = findViewById(R.id.review_content)
        actions = findViewById(R.id.review_actions)
        savedInstanceState?.let {
            dialogInFlight = it.getBoolean("inflight")
            it.getStringArray("checks")?.let { a -> checks.addAll(a) }
            shownId = it.getString("shown")
        }
        Proposals.recover(this)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("inflight", dialogInFlight)
        out.putStringArray("checks", checks.toTypedArray())
        out.putString("shown", shownId)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); showingLog = false }

    override fun onResume() {
        super.onResume()
        Proposals.onChange = { runOnUiThread { if (!dialogInFlight) render() } }
        render()
    }

    override fun onPause() { Proposals.onChange = null; super.onPause() }

    @Deprecated("back = Cancel on the review sheet")
    override fun onBackPressed() {
        if (showingLog) { showingLog = false; render(); return }
        val p = Proposals.active(this)
        if (p != null && p.optString("state") == DeleteGate.AWAITING && !dialogInFlight) { decide { onCancelSheet(p) }; return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------ render

    private fun render() {
        content.removeAllViews(); actions.removeAllViews(); actions.visibility = View.GONE
        banner.visibility = View.GONE
        if (showingLog) { renderLog(); return }
        val p = Proposals.active(this)
        if (p?.optString("id") != shownId) { checks.clear(); shownId = p?.optString("id"); answered = false }
        when (p?.optString("state")) {
            DeleteGate.PENDING -> renderPending(p)
            DeleteGate.SELECTED -> renderSelected(p)
            DeleteGate.AWAITING -> renderSheet(p)
            DeleteGate.EXECUTING -> renderExecuting(p)
            else -> renderIdle()
        }
        renderAccess()
        add(link(S.t("log_open")) { showingLog = true; render() }, top = 16)
    }

    private fun renderIdle() {
        title.text = S.t("title")
        subtitle.text = S.t("idle")
    }

    private fun renderPending(p: JSONObject) {
        val items = rows(p, "items")
        title.text = S.q("suggest_title", items.size, Fmt.size(p.optLong("bytes")))
        subtitle.text = S.t("pending_hint")
        reason(p)
        for (o in items) {
            val path = o.optString("path")
            add(CheckBox(this).apply {
                text = "${PathGuard.name(path)}\n${meta(o.optLong("bytes"), path)}"
                isChecked = path in checks
                contentDescription = "${PathGuard.name(path)}, ${Fmt.size(o.optLong("bytes"))}"
                setOnCheckedChangeListener { _, on -> if (on) checks.add(path) else checks.remove(path); renderPendingActions(p) }
            }, top = 4)
        }
        renderPendingActions(p)
    }

    private fun renderPendingActions(p: JSONObject) {
        actions.removeAllViews(); actions.visibility = View.VISIBLE
        val id = p.optString("id")
        val n = checks.size
        val bytes = rows(p, "items").filter { it.optString("path") in checks }.sumOf { it.optLong("bytes") }
        addAction(button(if (n == 0) S.t("tick_some") else S.q("continue_n", n, Fmt.size(bytes)), primary = true, enabled = n > 0) {
            val r = Proposals.select(this, id, checks.toList())
            if (r.has("error")) toast(r.optString("error")) else { checks.clear(); render() }
        })
        addAction(button(S.t("trash_now"), enabled = n > 0) { manual(id, "trash") })
        addAction(button(S.t("delete_now"), danger = true, enabled = n > 0) { manual(id, "delete") })
        addAction(button(S.t("keep_all"), text = true) { Proposals.dismiss(this, id); render() })
    }

    /** Her own Trash/Delete: freeze + move to awaiting_confirm, then the same review sheet decides. */
    private fun manual(id: String, mode: String) {
        val r = Proposals.manual(this, id, checks.toList(), mode)
        if (r.has("error")) toast(r.optString("error")) else { checks.clear(); answered = false; render() }
    }

    private fun renderSelected(p: JSONObject) {
        val sel = rows(p, "selected")
        title.text = S.q("selected_title", sel.size, Fmt.size(p.optLong("bytes_selected")))
        subtitle.text = S.t("selected_hint")
        if (p.optString("result") == "dialog_cancelled") showBanner(S.t("was_cancelled"), Tone.NEUTRAL)
        reason(p)
        for (o in sel) add(fileRow(o), top = 4)
        actions.visibility = View.VISIBLE
        val id = p.optString("id")
        addAction(button(S.t("adjust")) { Proposals.adjust(this, id); render() })
        addAction(button(S.t("cancel_all"), text = true) { Proposals.cancel(this, id); render() })
    }

    private fun renderExecuting(p: JSONObject) {
        title.text = S.t("executing")
        subtitle.text = S.t("executing_hint")
        // The app was killed while the system dialog was up, or the dialog never came back: offer to finish.
        if (!dialogInFlight && Proposals.Exec.get(this)?.optBoolean("launched") == true) {
            actions.visibility = View.VISIBLE
            addAction(button(S.t("finish_check")) { show(Proposals.complete(this, Proposals.Dialog.LOST)) })
        }
    }

    // ------------------------------------------------------------ the review sheet

    private fun renderSheet(p: JSONObject) {
        val id = p.optString("id")
        val mode = p.optString("mode")
        val trash = mode == "trash"
        val rc = Proposals.preview(this, id) ?: return renderIdle()
        val key = id + "|" + p.optLong("requested_at")
        if (key != sheetKey) { sheetKey = key; answered = false }
        val acting = rc.acting
        val total = acting.sumOf { it.bytes }
        title.text = S.q(if (trash) "sheet_title_trash" else "sheet_title_delete", acting.size, Fmt.size(total))
        subtitle.text = if (p.optString("requested_by") == "user_app") S.t("sheet_by_you") else S.t("sheet_by_genie")
        showBanner(S.t(if (trash) "sheet_mode_trash" else "sheet_mode_delete"), if (trash) Tone.PRIMARY else Tone.DANGER)
        reason(p)

        // By type + total.
        add(label(S.t("by_type")), top = 12)
        val agg = LinkedHashMap<String, LongArray>()
        for (it in acting) agg.getOrPut(Scanner.kind(it.path)) { LongArray(2) }.let { a -> a[0]++; a[1] += it.bytes }
        for ((k, a) in agg) add(pairRow(S.kind(k), S.q("type_count", a[0].toInt(), Fmt.size(a[1]))), top = 2)
        add(pairRow(S.t("total"), Fmt.size(total), strong = true), top = 6)

        // Every file, marked Trash (restorable) or permanent.
        add(label(S.t("files")), top = 16)
        for (it in rc.media) add(fileRow(it.path, it.bytes, if (trash) S.t("tag_trash") else S.t("tag_perm"), !trash), top = 4)
        for (it in rc.direct) add(fileRow(it.path, it.bytes, S.t("tag_perm"), true), top = 4)
        val untouched = rc.failed.size + rc.skipped.size
        if (untouched > 0) {
            add(label(S.q("untouched", untouched)), top = 16)
            for ((item, why) in rc.failed + rc.skipped) add(fileRow(item.path, item.bytes, S.why(why), false), top = 4)
        }
        add(small(S.t("caveat")), top = 16)

        actions.visibility = View.VISIBLE
        addAction(button(S.t(if (trash) "approve_trash" else "approve_delete"), primary = trash, danger = !trash,
            enabled = acting.isNotEmpty()) { decide { onApprove(p) } })
        addAction(button(S.t("adjust")) { decide { onAdjustSheet(p) } })
        addAction(button(S.t("cancel"), text = true) { decide { onCancelSheet(p) } })
    }

    private fun decide(f: () -> Unit) { if (!answered && !dialogInFlight) { answered = true; f() } }

    private fun onApprove(p: JSONObject) {
        val id = p.optString("id"); val mode = p.optString("mode")
        if (mode == "delete") {
            // Permanent: one more explicit tap, with the count (SG kept a second confirm for Delete forever).
            val n = Proposals.preview(this, id)?.acting?.size ?: 0
            AlertDialog.Builder(this).setMessage(S.q("really_delete", n))
                .setPositiveButton(S.t("approve_delete")) { _, _ -> runApprove(p) }
                .setNegativeButton(S.t("back")) { _, _ -> answered = false }
                .setOnCancelListener { answered = false }
                .show()
            return
        }
        runApprove(p)
    }

    private fun runApprove(p: JSONObject) {
        val rec = Proposals.approve(this, p.optString("id"), p.optString("mode"), p.optString("selected_hash"))
        if (rec.has("error")) { toast(S.t("changed")); answered = false; render(); return }
        val uris = rec.optJSONArray("uris") ?: JSONArray()
        // No media (mediaUris is empty below API 30): the sheet's Approve was the confirmation.
        if (uris.length() == 0 || Build.VERSION.SDK_INT < 30) { show(Proposals.complete(this, Proposals.Dialog.NONE)); return }
        launchSystemDialog(p.optString("id"), rec.optString("mode"), uris)
    }

    /** Media: Android's own Trash/Delete dialog is the step that removes them (and confirms once more). */
    @android.annotation.TargetApi(30)
    private fun launchSystemDialog(id: String, mode: String, uris: JSONArray) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val list = (0 until uris.length()).map { Uri.parse(uris.getString(it)) }
            val req = if (mode == "trash") MediaStore.createTrashRequest(contentResolver, list, true)
                else MediaStore.createDeleteRequest(contentResolver, list)
            // Cancelled over the link in the meantime: show nothing (the execution is already closed and logged).
            if (!Proposals.Exec.markLaunched(this, id)) { render(); return }
            dialogInFlight = true
            @Suppress("DEPRECATION") startIntentSenderForResult(req.intentSender, REQ_EXEC, null, 0, 0, 0)
        } catch (_: Throwable) {
            dialogInFlight = false
            show(Proposals.complete(this, Proposals.Dialog.FAILED))
        }
    }

    private fun onCancelSheet(p: JSONObject) {
        Proposals.sheetCancel(this, p.optString("id"))
        finishStep(p, S.t("cancelled_nothing"))
    }

    private fun onAdjustSheet(p: JSONObject) {
        Proposals.sheetAdjust(this, p.optString("id"))
        finishStep(p, S.t("adjusted"))
    }

    /** After a decision: back to the genie's chat when the person turned auto-return on, else stay. */
    private fun finishStep(p: JSONObject, msg: String) {
        val ret = p.optString("return_url")
        if (StoragePrefs.autoReturn(this) && ReturnUrl.valid(ret)) try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ret)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            toast(msg); finish(); return
        } catch (_: Throwable) {}
        toast(msg); render()
    }

    @Deprecated("Activity result for the system trash/delete dialog, the SAF picker, All-files settings")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_EXEC -> {
                dialogInFlight = false
                // Cancelling the system dialog means STOP: nothing at all is deleted.
                show(Proposals.complete(this, if (resultCode == RESULT_OK) Proposals.Dialog.OK else Proposals.Dialog.CANCELLED))
            }
            REQ_TREE -> {
                val u = data?.data
                if (resultCode == RESULT_OK && u != null) toast(S.t(if (StoragePrefs.addFolder(this, u)) "folder_added" else "folder_bad"))
                render()
            }
            REQ_ALLFILES -> render()
        }
    }

    private fun show(o: Proposals.Outcome?) {
        if (o == null) { render(); return }
        val msg = if (o.cancelled) S.t("cancelled_nothing") else buildString {
            if (o.freed > 0) append(S.q("freed", o.deleted, Fmt.size(o.freed))).append('\n')
            if (o.trashed > 0) append(S.q("trashed", Fmt.size(o.trashed))).append('\n')
            if (o.skipped > 0) append(S.q("skipped", o.skipped)).append('\n')
            if (o.failed > 0) append(S.q("failed", o.failed))
            if (isEmpty()) append(S.t("nothing_done"))
        }.trim()
        val p = shownId?.let { Proposals.get(this, it) }
        val ret = p?.optString("return_url") ?: ""
        if (StoragePrefs.autoReturn(this) && ReturnUrl.valid(ret)) try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ret)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show(); finish(); return
        } catch (_: Throwable) {}
        if (!isFinishing) AlertDialog.Builder(this).setMessage(msg).setPositiveButton(S.t("ok"), null).show()
        render()
    }

    // ------------------------------------------------------------ storage access

    private fun renderAccess() {
        add(label(S.t("access")), top = 24)
        val mode = if (StoragePrefs.allFilesMode(this)) S.t("mode_all") else S.t("mode_scoped")
        add(small(mode), top = 2)
        if (Build.VERSION.SDK_INT >= 30 && !StoragePrefs.allFilesGranted()) {
            add(button(S.t("grant_all")) {
                val i = StoragePrefs.allFilesIntent(this)
                if (i == null) AlertDialog.Builder(this).setMessage(S.t("xor_blocked")).setPositiveButton(S.t("ok"), null).show()
                else AlertDialog.Builder(this).setMessage(S.t("grant_all_why"))
                    .setPositiveButton(S.t("grant_all")) { _, _ -> try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_ALLFILES) } catch (_: Throwable) {} }
                    .setNegativeButton(S.t("back"), null).show()
            }, top = 4)
        }
        if (StoragePrefs.allFilesGranted() && StoragePrefs.xorHolds(this)) add(small(S.t("xor_scoped")), top = 4)
        for ((rel, tree) in StoragePrefs.folders(this)) add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "/$rel"; textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(S.t("remove"), text = true) { StoragePrefs.removeFolder(this@ReviewActivity, tree); render() })
        }, top = 2)
        add(button(S.t("pick_folder")) {
            try { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE) }
            catch (_: Throwable) { toast(S.t("folder_bad")) }
        }, top = 4)
        add(small(S.t("app_dir")), top = 4)
    }

    // ------------------------------------------------------------ deletion log

    private fun renderLog() {
        title.text = S.t("log_title")
        subtitle.text = S.t("log_hint")
        val rows = DeletionLog.rows(this)
        if (rows.isEmpty()) add(small(S.t("log_empty")), top = 8)
        for (r in rows.takeLast(LOG_ROWS).asReversed()) {
            val tag = when (r.optString("result")) { "ok" -> if (r.optString("mode") == "trash") S.t("tag_trashed") else S.t("tag_deleted")
                "failed" -> S.t("tag_failed"); else -> S.t("tag_skipped") }
            add(fileRow(r.optString("path"), r.optLong("bytes"), tag + (r.optString("why").takeIf { it.isNotEmpty() }?.let { " · " + S.why(it) } ?: ""),
                r.optString("result") == "ok" && r.optString("mode") == "delete"), top = 4)
        }
        if (rows.size > LOG_ROWS) add(small(S.q("log_more", rows.size - LOG_ROWS)), top = 8)
        add(link(S.t("back")) { showingLog = false; render() }, top = 16)
    }

    // ------------------------------------------------------------ small views

    private enum class Tone { PRIMARY, DANGER, NEUTRAL }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun add(v: View, top: Int = 0) = content.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun addAction(v: View) = actions.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    private fun showBanner(s: String, tone: Tone) {
        banner.text = s; banner.visibility = View.VISIBLE
        banner.setBackgroundColor(when (tone) { Tone.PRIMARY -> 0x331A73E8; Tone.DANGER -> 0x44D93025; Tone.NEUTRAL -> 0x22888888 })
    }

    private fun reason(p: JSONObject) {
        val r = p.optString("reason")
        if (r.isNotEmpty()) add(small(S.t("reason") + " " + r).apply { setTypeface(typeface, Typeface.ITALIC) }, top = 8)
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setTypeface(typeface, Typeface.BOLD); isAllCaps = true }
    private fun small(s: String) = TextView(this).apply { text = s; textSize = 13f; alpha = 0.8f }
    private fun link(s: String, f: () -> Unit) = button(s, text = true, f = f)

    private fun pairRow(l: String, r: String, strong: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply { text = l; textSize = 15f; if (strong) setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = r; textSize = 15f; if (strong) setTypeface(typeface, Typeface.BOLD) })
    }

    private fun fileRow(o: JSONObject) = fileRow(o.optString("path"), o.optLong("bytes"), null, false)

    private fun fileRow(path: String, bytes: Long, tag: String?, danger: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundColor(0x14888888)
        addView(TextView(context).apply { text = PathGuard.name(path); textSize = 15f })
        addView(TextView(context).apply {
            text = meta(bytes, path) + (tag?.let { " · $it" } ?: "")
            textSize = 12f; alpha = 0.85f
            if (danger) setTextColor(Color.rgb(0xF2, 0x8B, 0x82))
        })
        contentDescription = "${PathGuard.name(path)}, ${Fmt.size(bytes)}" + (tag?.let { ", $it" } ?: "")
    }

    private fun meta(bytes: Long, path: String) = "${Fmt.size(bytes)} · ${S.kind(Scanner.kind(path))} · /${PathGuard.parent(path)}"

    private fun button(s: String, primary: Boolean = false, danger: Boolean = false, text: Boolean = false, enabled: Boolean = true,
                       f: () -> Unit) = Button(this).apply {
        this.text = s; isAllCaps = false; isEnabled = enabled
        minHeight = dp(48)
        when {
            danger -> { setBackgroundColor(if (enabled) Color.rgb(0xB3, 0x26, 0x1E) else 0x33B3261E); setTextColor(Color.WHITE) }
            primary -> { setBackgroundColor(if (enabled) Color.rgb(0x1A, 0x73, 0xE8) else 0x331A73E8); setTextColor(Color.WHITE) }
            text -> setBackgroundColor(Color.TRANSPARENT)
        }
        setOnClickListener { f() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun rows(p: JSONObject, k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
}

/** The review screen's text, English + Hebrew (by device locale). No file contents, no nonces, no hashes. */
internal object S {
    private val he get() = Locale.getDefault().language.let { it == "iw" || it == "he" }

    private val EN = mapOf(
        "title" to "Storage", "idle" to "No suggestion from your genie right now.",
        "suggest_title" to "Your genie suggests deleting %d files (%s)",
        "pending_hint" to "Nothing is ticked. Tick what may go, then Continue. Nothing is deleted until you approve it here.",
        "tick_some" to "Tick files to continue", "continue_n" to "Continue with %d files (%s)",
        "trash_now" to "Move ticked to Trash…", "delete_now" to "Delete ticked forever…", "keep_all" to "Keep everything",
        "selected_title" to "%d files chosen (%s)",
        "selected_hint" to "Waiting for your genie. When it asks, this phone shows the final review and you decide.",
        "was_cancelled" to "You cancelled the last review. Nothing was deleted.",
        "adjust" to "Make changes", "cancel_all" to "Cancel the suggestion", "cancel" to "Cancel",
        "executing" to "Working…", "executing_hint" to "Android may ask you once more.",
        "finish_check" to "Check what happened",
        "sheet_title_trash" to "Move %d files to Trash (%s)?", "sheet_title_delete" to "Delete %d files forever (%s)?",
        "sheet_by_genie" to "Your genie asked for this. Only your tap below decides.",
        "sheet_by_you" to "You chose these files.",
        "sheet_mode_trash" to "Trash: restorable for about 30 days. The space is freed only when the Trash is emptied. Files that cannot go to Trash are left alone.",
        "sheet_mode_delete" to "Delete forever: NOT restorable.",
        "by_type" to "By type", "type_count" to "%d · %s", "total" to "Total", "files" to "Every file",
        "tag_trash" to "to Trash (restorable)", "tag_perm" to "permanent",
        "untouched" to "%d files will NOT be touched",
        "caveat" to "Honest note: when Genie access and All-files access are both on, the genie's shell may reach shared storage without this review.",
        "approve_trash" to "Approve: move to Trash", "approve_delete" to "Approve: delete forever",
        "really_delete" to "Delete %d files forever? This cannot be undone.", "back" to "Back",
        "changed" to "The list changed. Please review it again.",
        "cancelled_nothing" to "Cancelled. Nothing was deleted.", "adjusted" to "Back to the list. Tick again.",
        "freed" to "%d files deleted, %s freed", "trashed" to "%s moved to Trash (freed when the Trash is emptied)",
        "skipped" to "%d skipped", "failed" to "%d could not be deleted", "nothing_done" to "Nothing was changed.", "ok" to "OK",
        "access" to "Storage access",
        "mode_all" to "All files: your genie may list, read, upload and suggest across shared storage.",
        "mode_scoped" to "Limited: only LinuxDesk's own folder and the folders you picked below.",
        "grant_all" to "Allow All-files access…",
        "grant_all_why" to "With All-files access your genie can list, read and upload files anywhere in shared storage. Deleting always needs your Approve here.",
        "xor_blocked" to "The All-files guard is on and Genie access is on. Turn Genie access off first (Pair screen).",
        "xor_scoped" to "All-files access is granted, but the All-files guard keeps your genie limited while Genie access is on.",
        "pick_folder" to "Pick a folder your genie may use…", "remove" to "Remove",
        "folder_added" to "Folder added.", "folder_bad" to "That folder cannot be used (only internal shared storage).",
        "app_dir" to "Always available: Android/data/com.fire17.linuxdesk/files",
        "log_open" to "Deletion log", "log_title" to "Deletion log",
        "log_hint" to "Every file ever acted on, newest first. Kept on this phone; your genie can read it.",
        "log_empty" to "Nothing deleted yet.", "log_more" to "…and %d older rows",
        "tag_trashed" to "moved to Trash", "tag_deleted" to "deleted", "tag_failed" to "failed", "tag_skipped" to "not touched",
        "reason" to "Genie's reason:",
        "k_video" to "video", "k_image" to "photo", "k_audio" to "audio", "k_apk" to "app package", "k_archive" to "archive",
        "k_document" to "document", "k_other" to "other",
        "w_missing" to "gone", "w_changed" to "changed since", "w_non_media" to "cannot go to Trash", "w_not_media_now" to "not media any more",
        "w_dialog_cancelled" to "cancelled", "w_dialog_failed" to "dialog failed", "w_delete_failed" to "delete failed",
        "w_still_there" to "still there", "w_interrupted" to "interrupted", "w_cancelled" to "cancelled",
    )

    private val HE = mapOf(
        "title" to "אחסון", "idle" to "אין כרגע הצעה מהג'יני שלך.",
        "suggest_title" to "הג'יני שלך מציע למחוק %d קבצים (%s)",
        "pending_hint" to "שום דבר לא מסומן. סמנו מה מותר למחוק ואז המשך. שום דבר לא נמחק עד שתאשרו כאן.",
        "tick_some" to "סמנו קבצים כדי להמשיך", "continue_n" to "המשך עם %d קבצים (%s)",
        "trash_now" to "העבר מסומנים לאשפה…", "delete_now" to "מחק מסומנים לצמיתות…", "keep_all" to "השאר הכול",
        "selected_title" to "נבחרו %d קבצים (%s)",
        "selected_hint" to "ממתין לג'יני. כשהוא יבקש, הטלפון יציג סקירה אחרונה ואתם תחליטו.",
        "was_cancelled" to "ביטלתם את הסקירה האחרונה. שום דבר לא נמחק.",
        "adjust" to "שינויים", "cancel_all" to "בטל את ההצעה", "cancel" to "ביטול",
        "executing" to "עובד…", "executing_hint" to "ייתכן שאנדרואיד ישאל פעם נוספת.",
        "finish_check" to "בדוק מה קרה",
        "sheet_title_trash" to "להעביר %d קבצים לאשפה (%s)?", "sheet_title_delete" to "למחוק %d קבצים לצמיתות (%s)?",
        "sheet_by_genie" to "הג'יני ביקש זאת. רק הלחיצה שלכם למטה מחליטה.",
        "sheet_by_you" to "אתם בחרתם את הקבצים האלה.",
        "sheet_mode_trash" to "אשפה: ניתן לשחזר כ-30 יום. המקום מתפנה רק כשהאשפה מתרוקנת. קבצים שלא יכולים לעבור לאשפה לא ייגעו.",
        "sheet_mode_delete" to "מחיקה לצמיתות: לא ניתן לשחזר.",
        "by_type" to "לפי סוג", "type_count" to "%d · %s", "total" to "סה\"כ", "files" to "כל הקבצים",
        "tag_trash" to "לאשפה (ניתן לשחזר)", "tag_perm" to "לצמיתות",
        "untouched" to "%d קבצים לא ייגעו",
        "caveat" to "הערה כנה: כשגישת ג'יני וגישה לכל הקבצים פעילות יחד, ה-shell של הג'יני עשוי להגיע לאחסון המשותף בלי הסקירה הזו.",
        "approve_trash" to "אישור: העבר לאשפה", "approve_delete" to "אישור: מחק לצמיתות",
        "really_delete" to "למחוק %d קבצים לצמיתות? אי אפשר לבטל.", "back" to "חזרה",
        "changed" to "הרשימה השתנתה. נא לעבור עליה שוב.",
        "cancelled_nothing" to "בוטל. שום דבר לא נמחק.", "adjusted" to "חזרה לרשימה. סמנו שוב.",
        "freed" to "נמחקו %d קבצים, התפנו %s", "trashed" to "%s הועברו לאשפה (יתפנה כשהאשפה תתרוקן)",
        "skipped" to "%d דולגו", "failed" to "%d לא נמחקו", "nothing_done" to "שום דבר לא השתנה.", "ok" to "אישור",
        "access" to "גישה לאחסון",
        "mode_all" to "כל הקבצים: הג'יני יכול להציג, לקרוא, להעלות ולהציע בכל האחסון המשותף.",
        "mode_scoped" to "מוגבל: רק התיקייה של LinuxDesk והתיקיות שבחרתם למטה.",
        "grant_all" to "אפשר גישה לכל הקבצים…",
        "grant_all_why" to "עם גישה לכל הקבצים הג'יני יכול להציג, לקרוא ולהעלות קבצים בכל האחסון המשותף. מחיקה תמיד דורשת את האישור שלכם כאן.",
        "xor_blocked" to "שומר כל-הקבצים פעיל וגישת ג'יני פעילה. כבו קודם את גישת הג'יני (מסך הצימוד).",
        "xor_scoped" to "ניתנה גישה לכל הקבצים, אבל שומר כל-הקבצים משאיר את הג'יני מוגבל כל עוד גישת ג'יני פעילה.",
        "pick_folder" to "בחרו תיקייה שהג'יני יוכל להשתמש בה…", "remove" to "הסר",
        "folder_added" to "התיקייה נוספה.", "folder_bad" to "אי אפשר להשתמש בתיקייה הזו (רק באחסון המשותף הפנימי).",
        "app_dir" to "תמיד זמין: Android/data/com.fire17.linuxdesk/files",
        "log_open" to "יומן מחיקות", "log_title" to "יומן מחיקות",
        "log_hint" to "כל קובץ שטופל אי פעם, החדש ראשון. נשמר בטלפון; הג'יני יכול לקרוא אותו.",
        "log_empty" to "עוד לא נמחק דבר.", "log_more" to "…ועוד %d שורות ישנות",
        "tag_trashed" to "הועבר לאשפה", "tag_deleted" to "נמחק", "tag_failed" to "נכשל", "tag_skipped" to "לא נגע",
        "reason" to "הסיבה של הג'יני:",
        "k_video" to "וידאו", "k_image" to "תמונה", "k_audio" to "שמע", "k_apk" to "חבילת אפליקציה", "k_archive" to "ארכיון",
        "k_document" to "מסמך", "k_other" to "אחר",
        "w_missing" to "נעלם", "w_changed" to "השתנה מאז", "w_non_media" to "לא יכול לעבור לאשפה", "w_not_media_now" to "כבר לא מדיה",
        "w_dialog_cancelled" to "בוטל", "w_dialog_failed" to "החלון נכשל", "w_delete_failed" to "המחיקה נכשלה",
        "w_still_there" to "עדיין קיים", "w_interrupted" to "הופסק", "w_cancelled" to "בוטל",
    )

    fun t(k: String): String = (if (he) HE[k] else null) ?: EN[k] ?: k
    fun q(k: String, vararg a: Any): String = try { String.format(Locale.getDefault(), t(k), *a) } catch (_: Throwable) { t(k) }
    fun kind(k: String) = t("k_$k")
    fun why(w: String) = EN["w_$w"]?.let { t("w_$w") } ?: w
}
