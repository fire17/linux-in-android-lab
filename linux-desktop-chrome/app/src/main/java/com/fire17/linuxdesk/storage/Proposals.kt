package com.fire17.linuxdesk.storage

import android.content.Context
import android.content.Intent
import android.os.StatFs
import com.fire17.linuxdesk.genie.Binding
import com.fire17.linuxdesk.genie.GenieNotes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID

/**
 * Deletion suggestions from the genie: a port of Storage Genie's Proposals.kt (v0.4 gate + v0.5.2 review
 * sheet) with LinuxDesk's owner rule (spec 01 §1.3 "Delete rule", §4 rule 6): DELETES ARE IN-APP-CONFIRM ONLY.
 * The state machine and its checks live in [DeleteGate] (pure, unit-tested); this file stores and acts.
 *
 *   fs.propose_delete -> pending ──(in-app Continue, or preselect:true)──> selected (frozen list + hash + nonce)
 *   fs.execute(nonce, selected_hash) -> awaiting_confirm   ← nothing is deleted; the review sheet is posted
 *   review sheet Approve (a tap on THIS phone) -> executing (+ MediaStore system dialog for media) -> done
 *   review sheet Cancel / back -> selected (result=dialog_cancelled) · Make changes -> pending
 *
 * Nothing here deletes anything except [complete], which runs only after [approve], which only
 * ReviewActivity calls (never the link). Every acted-on file is logged in [DeletionLog] (append-only).
 * File names never appear in notifications or logcat; nonces and hashes never appear in notifications.
 */
object Proposals {
    private const val KEEP = 30
    private val rng = SecureRandom()

    private fun file(ctx: Context) = File(ctx.filesDir, "proposals.json")

    @Synchronized
    fun all(ctx: Context): JSONArray = try { JSONArray(file(ctx).readText()) } catch (_: Throwable) { JSONArray() }

    @Synchronized
    private fun save(ctx: Context, arr: JSONArray) {
        // Atomic replace, so a crash mid-write never leaves a half proposal list.
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(arr.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
    }

    /** Listeners (ReviewActivity) refresh when anything changes. */
    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { try { onChange?.invoke() } catch (_: Throwable) {} }

    /** The proposal the screen shows: the newest one still pending, selected, awaiting_confirm or executing. */
    fun active(ctx: Context): JSONObject? {
        val a = all(ctx)
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).optString("state") in DeleteGate.LIVE) return a.getJSONObject(i)
        return null
    }

    fun find(a: JSONArray, id: String): JSONObject? {
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("id") == id) return it }
        return null
    }

    fun get(ctx: Context, id: String): JSONObject? = find(all(ctx), id)

    private fun err(code: String) = JSONObject().put("error", code)

    private fun items(p: JSONObject, key: String): List<DeleteGate.Item> {
        val arr = p.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o ->
            DeleteGate.Item(o.optString("path"), o.optLong("bytes"), o.optBoolean("media")) } }
    }

    private fun reviewIntent(ctx: Context, id: String) = Intent(ctx, ReviewActivity::class.java)
        .putExtra(ReviewActivity.EXTRA_ID, id)

    // ------------------------------------------------------------ genie side (fs.* verbs)

    /** fs.propose_delete. [preselect] = freeze the genie's whole list now (still needs the in-app Approve). */
    @Synchronized
    fun create(ctx: Context, paths: List<String>, reason: String, preselect: Boolean = false): JSONObject {
        if (!Scanner.hasSeen()) return err("bad_args").put("detail", "run fs.scan or fs.list first")
        val a = all(ctx)
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            val st = it.optString("state")
            if (st == DeleteGate.AWAITING || st == DeleteGate.EXECUTING) return err("busy").put("id", it.optString("id"))
        }
        val plan = DeleteGate.plan(paths, { Scanner.seen(it) }, { FsAccess.sizeNow(ctx, it) })
        if (plan.error == "too_many") return err("bad_args").put("detail", "at most ${DeleteGate.MAX_PATHS} paths per proposal")
        if (plan.error != null) return err("nothing_valid").put("rejected", JSONArray(plan.rejected))
        val id = UUID.randomUUID().toString().take(8)
        val arr = JSONArray()
        for (it in plan.items) arr.put(JSONObject().put("path", it.path).put("bytes", it.bytes))
        val prop = JSONObject().put("id", id).put("state", DeleteGate.PENDING).put("reason", clean(reason, 300))
            .put("items", arr).put("bytes", plan.bytes).put("t", now())
            .put("genie", (try { Binding.genie(ctx) } catch (_: Throwable) { null }) ?: "")
        // A newer suggestion replaces any older one still waiting (one list at a time). A replaced `selected`
        // loses its nonce, so nothing can execute it any more.
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            if (DeleteGate.next(it.optString("state"), DeleteGate.Ev.REPLACE) != null) {
                it.put("state", DeleteGate.DISMISSED).put("result", "replaced"); it.remove("nonce")
                GenieNotes.clearProposal(ctx, it.optString("id"))
            }
        }
        a.put(prop)
        while (a.length() > KEEP) a.remove(0)
        save(ctx, a)
        val reply = JSONObject().put("ok", true).put("id", id).put("count", plan.items.size).put("bytes", plan.bytes)
            .put("rejected", JSONArray(plan.rejected))
        if (preselect) {
            val r = select(ctx, id, plan.items.map { it.path }, by = "genie")
            if (r.has("error")) return r
            reply.put("state", DeleteGate.SELECTED)
        } else reply.put("state", DeleteGate.PENDING)
        GenieNotes.suggest(ctx, id, plan.items.size, plan.bytes, reviewIntent(ctx, id))
        changed()
        return reply
    }

    /**
     * Freeze the ticked list: deletes NOTHING. Called by ReviewActivity's Continue (by="user_app") or by
     * [create] with preselect (by="genie"). The nonce is made here, by the phone.
     */
    @Synchronized
    fun select(ctx: Context, id: String, chosen: List<String>, by: String = "user_app"): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.SELECT) == null) return err("not_pending").put("state", p.optString("state"))
        val byPath = LinkedHashMap<String, Long>()
        for (it in items(p, "items")) byPath[it.path] = it.bytes
        val pick = chosen.filter { it in byPath }.distinct()
        if (pick.isEmpty()) return err("nothing_ticked")
        val media = FsAccess.mediaUris(ctx, pick)
        val sel = JSONArray(); var total = 0L; var perm = 0
        for (path in pick) {
            val b = byPath[path]!!; val m = media.containsKey(path)
            if (!m) perm++
            total += b
            sel.put(JSONObject().put("path", path).put("bytes", b).put("media", m))
        }
        p.put("state", DeleteGate.SELECTED).put("selected", sel).put("bytes_selected", total).put("permanent_count", perm)
            .put("selected_hash", DeleteGate.hashOf(pick.map { it to byPath[it]!! })).put("nonce", DeleteGate.nonce(rng))
            .put("kept", JSONArray(byPath.keys.filter { it !in pick })).put("selected_at", now()).put("selected_by", by)
        p.remove("result")
        save(ctx, a)
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.SELECTED)
    }

    /**
     * fs.execute. Checks nonce + hash, then moves the proposal to `awaiting_confirm` and posts the heads-up
     * "Confirm: delete N files (X)". It NEVER deletes and never starts a deletion: only the review sheet's
     * Approve does ([approve]). [manual] = the person's own in-app Trash/Delete (same gate, same sheet).
     */
    @Synchronized
    fun execute(ctx: Context, cmd: JSONObject, manual: Boolean = false): JSONObject {
        val id = cmd.optString("id")
        val mode = cmd.optString("mode")
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        val ret = cmd.optString("return_url")
        if (ret.isNotEmpty() && !ReturnUrl.valid(ret)) return err("bad_return_url")
        val sel = items(p, "selected")
        val e = DeleteGate.checkExecute(p.optString("state"), mode, cmd.optString("nonce").ifEmpty { cmd.optString("confirm") },
            cmd.optString("selected_hash"), p.optString("nonce"), p.optString("selected_hash"), sel.map { it.path to it.bytes })
        if (e != null) return err(e).put("state", p.optString("state"))
        p.put("state", DeleteGate.AWAITING).put("mode", mode).put("requested_at", now())
            .put("requested_by", if (manual) "user_app" else "genie")
        if (ret.isNotEmpty()) p.put("return_url", ret) else p.remove("return_url")
        p.remove("result")
        save(ctx, a)
        if (!manual) GenieNotes.confirm(ctx, id, sel.size, sel.sumOf { it.bytes }, reviewIntent(ctx, id).putExtra(ReviewActivity.EXTRA_CONFIRM, true))
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.AWAITING)
    }

    /** The person ticked and tapped Trash/Delete in the app: freeze + execute, then the sheet shows at once. */
    @Synchronized
    fun manual(ctx: Context, id: String, chosen: List<String>, mode: String): JSONObject {
        val s = select(ctx, id, chosen, by = "user_app")
        if (s.has("error")) return s
        val p = get(ctx, id) ?: return err("no_such_proposal")
        return execute(ctx, JSONObject().put("id", id).put("mode", mode).put("nonce", p.optString("nonce"))
            .put("selected_hash", p.optString("selected_hash")), manual = true)
    }

    @Synchronized
    fun cancel(ctx: Context, id: String): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        val st = p.optString("state")
        val rec = Exec.get(ctx)
        val launched = rec != null && rec.optString("id") == id && rec.optBoolean("launched")
        if (DeleteGate.next(st, DeleteGate.Ev.CANCEL, launched) == null) return err("not_cancellable").put("state", st)
        if (st == DeleteGate.EXECUTING) {
            // Not launched yet: nothing was deleted. Log every row as skipped, like a cancelled dialog.
            rec?.let { logAll(ctx, it, "skipped", "cancelled") }
            Exec.clear(ctx)
        }
        p.put("state", DeleteGate.CANCELLED); p.remove("nonce")
        save(ctx, a)
        GenieNotes.clearProposal(ctx, id)
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.CANCELLED)
    }

    /** fs.adjust: `selected` back to `pending` (nonce cleared), as in SG. */
    @Synchronized
    fun adjust(ctx: Context, id: String): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.ADJUST) == null) return err("not_selected").put("state", p.optString("state"))
        toPending(p)
        save(ctx, a)
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.PENDING)
    }

    private fun toPending(p: JSONObject) {
        p.put("state", DeleteGate.PENDING)
        for (k in listOf("selected", "selected_hash", "nonce", "bytes_selected", "permanent_count", "result", "kept",
            "mode", "return_url", "requested_at", "requested_by", "selected_by")) p.remove(k)
    }

    /** "Keep everything" in the app. */
    @Synchronized
    fun dismiss(ctx: Context, id: String) {
        val a = all(ctx)
        val p = find(a, id) ?: return
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.DISMISS) == null) return
        p.put("state", DeleteGate.DISMISSED).put("kept", JSONArray(items(p, "items").map { it.path }))
        save(ctx, a)
        GenieNotes.clearProposal(ctx, id)
        changed()
    }

    fun freeBytes(): Long = try { StatFs(FsAccess.root.path).availableBytes } catch (_: Throwable) { -1L }

    /** fs.proposal: the SG v0.4/v0.5.2 `proposal` reply. nonce + selected_hash only while `selected`. */
    fun status(ctx: Context, id: String): JSONObject {
        val p = get(ctx, id) ?: return err("no_such_proposal")
        val st = p.optString("state")
        val r = JSONObject().put("ok", true).put("id", id).put("state", st)
            .put("reason", p.optString("reason"))
            .put("count", p.optJSONArray("items")?.length() ?: 0).put("bytes", p.optLong("bytes"))
            .put("items", p.optJSONArray("items") ?: JSONArray())
            .put("selected", p.optJSONArray("selected") ?: JSONArray())
            .put("bytes_selected", p.optLong("bytes_selected")).put("permanent_count", p.optInt("permanent_count"))
            .put("free_bytes", freeBytes())
            .put("deleted", p.optJSONArray("deleted") ?: JSONArray())
            .put("kept", p.optJSONArray("kept") ?: JSONArray())
            .put("failed", p.optJSONArray("failed") ?: JSONArray())
            .put("skipped", p.optJSONArray("skipped") ?: JSONArray())
            // freed_bytes = space actually free now. trashed_bytes = in the system Trash: NOT free until it is emptied.
            .put("freed_bytes", p.optLong("freed_bytes")).put("trashed_bytes", p.optLong("trashed_bytes"))
            .put("trashed", p.optLong("trashed_bytes") > 0)
            .put("confirm", "in_app")   // LinuxDesk: every deletion waits for the Approve tap on the phone
        if (st == DeleteGate.SELECTED) r.put("selected_hash", p.optString("selected_hash")).put("nonce", p.optString("nonce"))
        if (p.has("mode")) r.put("mode", p.optString("mode"))
        if (p.has("result")) r.put("result", p.optString("result"))
        return r
    }

    // ------------------------------------------------------------ the review sheet (ReviewActivity only)

    /** What the sheet will show for an `awaiting_confirm` proposal: a fresh re-check, nothing changed. */
    fun preview(ctx: Context, id: String): DeleteGate.Recheck? {
        val p = get(ctx, id) ?: return null
        if (p.optString("state") != DeleteGate.AWAITING) return null
        return DeleteGate.recheck(items(p, "selected"), p.optString("mode"), { FsAccess.sizeNow(ctx, it) })
    }

    /** The pending execution (one at a time), persisted so a process death can't lose it. */
    object Exec {
        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ld.exec", Context.MODE_PRIVATE)
        fun get(ctx: Context): JSONObject? = prefs(ctx).getString("rec", null)?.let { try { JSONObject(it) } catch (_: Throwable) { null } }
        fun put(ctx: Context, rec: JSONObject) { prefs(ctx).edit().putString("rec", rec.toString()).commit() }
        fun clear(ctx: Context) { prefs(ctx).edit().remove("rec").commit() }
        /** The system dialog is about to show: from now on only its answer finishes this execution. */
        /**
         * The system dialog for [id] is about to show. Under the Proposals lock, so a remote fs.cancel can't slip
         * in between: false = the execution is gone (cancelled meanwhile), and the caller must NOT show the dialog.
         * From true on, fs.cancel is refused and only the dialog's answer finishes this execution.
         */
        fun markLaunched(ctx: Context, id: String): Boolean = synchronized(Proposals) {
            val rec = get(ctx) ?: return false
            if (rec.optString("id") != id) return false
            put(ctx, rec.put("launched", true).put("launched_at", System.currentTimeMillis() / 1000))
            true
        }
    }

    /**
     * The person tapped Approve on the review sheet for [id] in [mode]; [shownHash] = the `selected_hash` of the
     * list the sheet showed, so a proposal changed behind the sheet's back is refused (`changed`). Re-checks the frozen hash and every file right now, then
     * records the execution. Returns the execution record (media `uris` need the system dialog; when there are
     * none, the caller calls [complete] with DIALOG_NONE), or an error object.
     */
    @Synchronized
    fun approve(ctx: Context, id: String, mode: String, shownHash: String): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.APPROVE) == null) return err("not_awaiting").put("state", p.optString("state"))
        if (p.optString("mode") != mode || !DeleteGate.constEq(p.optString("selected_hash"), shownHash)) return err("changed")
        val sel = items(p, "selected")
        if (sel.isEmpty() || DeleteGate.hashOf(sel.map { it.path to it.bytes }) != p.optString("selected_hash")) return err("hash_mismatch")
        if (Exec.get(ctx) != null) return err("busy")
        val rc = DeleteGate.recheck(sel, mode, { FsAccess.sizeNow(ctx, it) })
        val uriMap = FsAccess.mediaUris(ctx, rc.media.map { it.path })
        val media = JSONArray(); val uris = JSONArray(); val failed = JSONArray()
        for ((item, why) in rc.failed) failed.put(row(item).put("why", why))
        for (m in rc.media) {
            val u = uriMap[m.path]
            if (u == null) failed.put(row(m).put("why", "not_media_now")) else { media.put(row(m)); uris.put(u.toString()) }
        }
        val rec = JSONObject().put("id", id).put("mode", mode).put("genie", p.optString("genie"))
            .put("requested_by", p.optString("requested_by", "genie"))
            .put("media", media).put("uris", uris).put("direct", JSONArray(rc.direct.map { row(it) }))
            .put("failed", failed).put("skipped", JSONArray(rc.skipped.map { (item, why) -> row(item).put("why", why) }))
        p.put("state", DeleteGate.EXECUTING).put("approved_at", now()); p.remove("result")
        save(ctx, a)
        Exec.put(ctx, rec)
        GenieNotes.clearProposal(ctx, id)
        changed()
        return rec
    }

    private fun row(it: DeleteGate.Item) = JSONObject().put("path", it.path).put("bytes", it.bytes)

    /** Cancel / back on the review sheet: `selected` again (result=dialog_cancelled). Nothing is deleted. */
    @Synchronized
    fun sheetCancel(ctx: Context, id: String): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.SHEET_CANCEL) == null) return err("not_awaiting")
        logItems(ctx, p, items(p, "selected"), p.optString("mode"), "skipped", "dialog_cancelled")
        p.put("state", DeleteGate.SELECTED).put("result", "dialog_cancelled")
        for (k in listOf("mode", "return_url", "requested_at")) p.remove(k)
        save(ctx, a)
        GenieNotes.clearProposal(ctx, id)
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.SELECTED)
    }

    /** Make changes on the review sheet: back to `pending` (nonce cleared). Nothing is deleted. */
    @Synchronized
    fun sheetAdjust(ctx: Context, id: String): JSONObject {
        val a = all(ctx)
        val p = find(a, id) ?: return err("no_such_proposal")
        if (DeleteGate.next(p.optString("state"), DeleteGate.Ev.SHEET_ADJUST) == null) return err("not_awaiting")
        toPending(p)
        p.put("result", "adjust_requested")
        save(ctx, a)
        GenieNotes.clearProposal(ctx, id)
        changed()
        return JSONObject().put("ok", true).put("id", id).put("state", DeleteGate.PENDING)
    }

    /** What finished: shown to the person after the step. */
    class Outcome(val deleted: Int, val failed: Int, val skipped: Int, val freed: Long, val trashed: Long, val cancelled: Boolean)

    enum class Dialog { NONE, OK, CANCELLED, FAILED, INTERRUPTED, LOST }

    /**
     * Finish the pending execution. [d] = the system Trash/Delete dialog's answer: NONE (no media, the sheet's
     * Approve was the only step), OK, CANCELLED / FAILED (nothing at all is deleted, not even non-media), or
     * INTERRUPTED (the app died between Approve and the dialog: nothing deleted), or LOST (the app died while the
     * dialog was up: media judged by existence, non-media NOT deleted).
     */
    @Synchronized
    fun complete(ctx: Context, d: Dialog): Outcome? {
        val rec = Exec.get(ctx) ?: return null
        val id = rec.optString("id"); val mode = rec.optString("mode")
        fun rows(k: String) = rec.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } } ?: emptyList()
        fun log(o: JSONObject, result: String, why: String?) = DeletionLog.append(ctx, id, o.optString("path"), o.optLong("bytes"),
            mode, result, rec.optString("genie"), why, rec.optString("requested_by"))

        val a = all(ctx)
        val p = find(a, id)
        if (d == Dialog.CANCELLED || d == Dialog.FAILED || d == Dialog.INTERRUPTED) {
            val why = when (d) { Dialog.CANCELLED -> "dialog_cancelled"; Dialog.FAILED -> "dialog_failed"; else -> "interrupted" }
            logAll(ctx, rec, "skipped", why)
            p?.put("state", DeleteGate.SELECTED)?.put("result", why)
            p?.remove("mode")
            save(ctx, a); Exec.clear(ctx); changed()
            return Outcome(0, 0, 0, 0, 0, cancelled = true)
        }
        val deleted = ArrayList<String>(); val failed = ArrayList<String>(); val skipped = ArrayList<String>()
        var freed = 0L; var trashed = 0L
        rows("failed").forEach { failed.add(it.optString("path")); log(it, "failed", it.optString("why")) }
        rows("skipped").forEach { skipped.add(it.optString("path")); log(it, "skipped", it.optString("why")) }
        rows("media").forEach {
            // After the dialog, trashed files are renamed (.trashed-…) and deleted ones are gone: either way the
            // original path no longer exists. Still there = it did not happen.
            if (FsAccess.exists(ctx, it.optString("path"))) { failed.add(it.optString("path")); log(it, "failed", "still_there") }
            else {
                deleted.add(it.optString("path")); log(it, "ok", if (d == Dialog.LOST) "result_lost_file_gone" else null)
                if (mode == "trash") trashed += it.optLong("bytes") else freed += it.optLong("bytes")
            }
        }
        rows("direct").forEach {
            val path = it.optString("path")
            when {
                mode != "delete" -> { skipped.add(path); log(it, "skipped", "non_media") }
                d == Dialog.LOST -> { skipped.add(path); log(it, "skipped", "interrupted") }
                FsAccess.deleteFile(ctx, path, it.optLong("bytes")) -> { deleted.add(path); freed += it.optLong("bytes"); log(it, "ok", null) }
                else -> { failed.add(path); log(it, "failed", "delete_failed") }
            }
        }
        p?.put("state", DeleteGate.DONE)?.put("deleted", JSONArray(deleted))?.put("failed", JSONArray(failed))
            ?.put("skipped", JSONArray(skipped))?.put("freed_bytes", freed)?.put("trashed_bytes", trashed)
            ?.put("done_at", now())
        if (d == Dialog.LOST) p?.put("result", "interrupted")
        p?.remove("nonce")
        save(ctx, a); Exec.clear(ctx); changed()
        return Outcome(deleted.size, failed.size, skipped.size, freed, trashed, cancelled = false)
    }

    private fun logAll(ctx: Context, rec: JSONObject, result: String, why: String) {
        for (k in listOf("media", "direct", "failed", "skipped")) rec.optJSONArray(k)?.let { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                DeletionLog.append(ctx, rec.optString("id"), it.optString("path"), it.optLong("bytes"), rec.optString("mode"),
                    result, rec.optString("genie"), why, rec.optString("requested_by"))
            }
        }
    }

    private fun logItems(ctx: Context, p: JSONObject, list: List<DeleteGate.Item>, mode: String, result: String, why: String) {
        for (it in list) DeletionLog.append(ctx, p.optString("id"), it.path, it.bytes, mode, result, p.optString("genie"), why,
            p.optString("requested_by"))
    }

    /**
     * After a process death: an `executing` proposal with no execution record goes back to `selected`; one whose
     * system dialog was launched but never answered is finished as LOST (non-media is not deleted).
     */
    @Synchronized
    fun recover(ctx: Context) {
        val rec = Exec.get(ctx)
        if (rec != null) {
            // A launched dialog may still be answered (the activity is recreated with the result), so only give up
            // on it after 15 minutes.
            if (rec.optBoolean("launched") && now() - rec.optLong("launched_at") > 15 * 60) complete(ctx, Dialog.LOST)
            // Approve and the dialog launch happen in one UI step, so an unlaunched record at startup = a crash
            // in between: nothing was deleted, back to `selected`.
            else if (!rec.optBoolean("launched")) complete(ctx, Dialog.INTERRUPTED)
            return
        }
        val a = all(ctx); var dirty = false
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            if (DeleteGate.next(it.optString("state"), DeleteGate.Ev.INTERRUPT) != null) {
                it.put("state", DeleteGate.SELECTED).put("result", "interrupted"); it.remove("mode"); dirty = true
            }
        }
        if (dirty) save(ctx, a)
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** Strip control and bidi-override characters (the reason is shown on the phone), cap the length. */
    fun clean(s: String, max: Int): String = s.filter { c ->
        !(c < ' ' && c != '\n') && c != '\u007f' && c !in '‪'..'‮' && c !in '⁦'..'⁩'
    }.take(max)
}

/**
 * SG v0.5.2: where the app may send the person after the review, only when StoragePrefs.autoReturn is ON
 * (default OFF). ONLY a WhatsApp group invite, a wa.me number link, or the whatsapp:// send/chat forms.
 */
object ReturnUrl {
    private val RE = Regex("""(https://chat\.whatsapp\.com/[A-Za-z0-9]{16,32}|https://wa\.me/[0-9]{6,15}|whatsapp://send\?phone=[0-9]{6,15}|whatsapp://chat\?code=[A-Za-z0-9]{16,32})""")
    fun valid(s: String?): Boolean = !s.isNullOrEmpty() && s.length <= 120 && RE.matches(s)
}

/**
 * Append-only deletion log, `filesDir/deletion-log.jsonl`: one row per file per outcome, written (and fsynced)
 * as soon as the outcome is known. Never rewritten, never truncated. No update rows (LinuxDesk has no updater).
 * Row: {t, proposal, path, bytes, mode, result: ok|failed|skipped, genie, by:"review_sheet", requested_by, why?}.
 */
object DeletionLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "deletion-log.jsonl")

    @Synchronized
    fun append(ctx: Context, proposal: String, path: String, bytes: Long, mode: String, result: String,
               genie: String, why: String?, requestedBy: String?) {
        val row = JSONObject().put("t", System.currentTimeMillis() / 1000).put("proposal", proposal).put("path", path)
            .put("bytes", bytes).put("mode", mode).put("result", result).put("genie", genie).put("by", "review_sheet")
        if (!requestedBy.isNullOrEmpty()) row.put("requested_by", requestedBy)
        if (!why.isNullOrEmpty()) row.put("why", why)
        try {
            FileOutputStream(file(ctx), true).use { it.write((row.toString() + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
        } catch (_: Throwable) {}
    }

    /** Rows with t >= since, oldest first. */
    @Synchronized
    fun rows(ctx: Context, since: Long = 0L): List<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { l -> try { JSONObject(l) } catch (_: Throwable) { null } }
                .filter { it.optLong("t") >= since }
        } catch (_: Throwable) { emptyList() }
    }

    /** fs.log: at most the newest 1000 rows. */
    fun query(ctx: Context, since: Long): JSONObject {
        val r = rows(ctx, since)
        val keep = r.takeLast(1000)
        return JSONObject().put("ok", true).put("rows", JSONArray(keep)).put("total", r.size).put("truncated", keep.size < r.size)
    }
}

object Fmt {
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
