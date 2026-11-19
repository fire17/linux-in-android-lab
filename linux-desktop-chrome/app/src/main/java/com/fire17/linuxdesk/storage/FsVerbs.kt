package com.fire17.linuxdesk.storage

import android.content.Context
import com.fire17.linuxdesk.genie.Reply
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Every `fs.*` verb of `linuxdesk/1` (spec 01 §1.3). The contract with the wire lane (§3):
 * `FsVerbs.handle(ctx, cmd): Reply`. The Router echoes `id`; handlers never add it. Called from the Router's
 * worker thread (fs.scan can take seconds), never the main thread.
 *
 * Rules enforced here: every path goes through [PathGuard] (relative to /storage/emulated/0, no `..`, no
 * absolute, no NUL, no Android/data|obb except the app's own files dir) and then [FsAccess] (canonical
 * containment, scoped vs all-files mode). `fs.put_commit` never overwrites. `fs.execute` never deletes: it only
 * moves a proposal to `awaiting_confirm` and opens the review sheet. There is no verb that approves a deletion,
 * changes StoragePrefs, or picks SAF folders. File contents are never logged (fs.get/put bytes pass through).
 */
object FsVerbs {
    const val MAX_CHUNK = 512 * 1024
    const val MAX_HASH_BYTES = 256L * 1024 * 1024
    const val LIST_DEFAULT = 500
    const val LIST_MAX = 5000

    val VERBS = setOf("fs.scan", "fs.list", "fs.stat", "fs.get", "fs.put", "fs.put_commit", "fs.mkdir",
        "fs.propose_delete", "fs.proposal", "fs.execute", "fs.cancel", "fs.adjust", "fs.log")

    fun handle(ctx: Context, cmd: JSONObject): Reply = try {
        when (cmd.optString("cmd")) {
            "fs.scan" -> json(Scanner.scan(ctx, cmd.optInt("top", 40)))
            "fs.list" -> list(ctx, cmd)
            "fs.stat" -> stat(ctx, cmd)
            "fs.get" -> get(ctx, cmd)
            "fs.put" -> put(ctx, cmd)
            "fs.put_commit" -> commit(ctx, cmd)
            "fs.mkdir" -> mkdir(ctx, cmd)
            "fs.propose_delete" -> propose(ctx, cmd)
            "fs.proposal" -> needId(cmd) { json(Proposals.status(ctx, it)) }
            "fs.execute" -> needId(cmd) { json(Proposals.execute(ctx, cmd)) }
            "fs.cancel" -> needId(cmd) { json(Proposals.cancel(ctx, it)) }
            "fs.adjust" -> needId(cmd) { json(Proposals.adjust(ctx, it)) }
            "fs.log" -> json(DeletionLog.query(ctx, cmd.optLong("since", 0L)))
            else -> Reply.error("unknown_cmd")
        }
    } catch (_: OutOfMemoryError) {
        Reply.error("too_big")
    } catch (t: Throwable) {
        Reply.error("internal", t.javaClass.simpleName)   // never the message: it may hold a path
    }

    // ------------------------------------------------------------ helpers

    /** Wraps a Proposals/Scanner result; their `{error:…}` objects pass through as wire errors. */
    private fun json(o: JSONObject): Reply = Reply.Json(o)

    /**
     * The proposal id. The spec names it `id` (fs.proposal/execute/cancel/adjust), but `id` is also the frame's
     * multiplex id, an int. So: `proposal` wins; else a STRING `id` is the proposal (SG-style sequential clients);
     * an int `id` is only the frame id. Replies carry the proposal id as `proposal` too, because the Router
     * overwrites `id` with the frame id when it echoes it.
     */
    private inline fun needId(cmd: JSONObject, f: (String) -> Reply): Reply {
        val id = (cmd.opt("proposal") as? String) ?: (cmd.opt("id") as? String) ?: ""
        if (id.isEmpty()) return Reply.error("bad_args", "proposal missing")
        val sane = id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }
        if (!sane) return Reply.error("bad_args", "bad proposal id")
        if (cmd.opt("id") !is String) cmd.put("id", id)   // Proposals.execute reads cmd.id
        return withProposal(f(id))
    }

    private fun withProposal(r: Reply): Reply {
        if (r is Reply.Json) r.obj.optString("id").takeIf { it.isNotEmpty() }?.let { r.obj.put("proposal", it) }
        return r
    }

    private fun path(ctx: Context, cmd: JSONObject, key: String = "path", allowRoot: Boolean = false): Any =
        when (val r = PathGuard.check(cmd.opt(key) as? String ?: (if (allowRoot && !cmd.has(key)) "" else null),
            ctx.packageName, allowRoot)) {
            is PathGuard.Result.Ok -> r.rel
            is PathGuard.Result.Bad -> Reply.error(r.error, r.detail)
        }

    private fun node(ctx: Context, rel: String): Any = when (val l = FsAccess.locate(ctx, rel)) {
        is FsAccess.Loc.Ok -> l.node
        is FsAccess.Loc.Err -> Reply.error(l.error, l.detail)
    }

    private fun entry(name: String, i: FsAccess.Info) = JSONObject().put("name", name).put("dir", i.dir)
        .put("bytes", i.bytes).put("mtime", i.mtime).put("kind", if (i.dir) "dir" else Scanner.kind(name))

    // ------------------------------------------------------------ verbs

    private fun list(ctx: Context, cmd: JSONObject): Reply {
        val limit = cmd.optInt("limit", LIST_DEFAULT).coerceIn(1, LIST_MAX)
        val rel = path(ctx, cmd, allowRoot = true).let { if (it is Reply) return it else it as String }
        if (rel.isEmpty() && !StoragePrefs.allFilesMode(ctx)) {
            // Scoped mode: the storage root lists only the roots LinuxDesk may use, as folders.
            val roots = FsAccess.scopedRoots(ctx)
            val arr = JSONArray()
            for (r in roots.take(limit)) arr.put(JSONObject().put("name", r).put("dir", true).put("bytes", 0L)
                .put("mtime", 0L).put("kind", "dir").put("root", true))
            return Reply.Json(JSONObject().put("ok", true).put("path", "").put("entries", arr)
                .put("truncated", roots.size > limit).put("fs_mode", "scoped"))
        }
        val n = node(ctx, rel).let { if (it is Reply) return it else it as FsAccess.Node }
        val i = FsAccess.info(ctx, n) ?: return Reply.error("not_found")
        if (!i.dir) return Reply.error("bad_args", "not a folder")
        val (kids, truncated) = FsAccess.children(ctx, n, limit)
        val arr = JSONArray(); val files = HashMap<String, Long>()
        for (k in kids) {
            arr.put(entry(k.name, k.info))
            if (!k.info.dir && !k.name.startsWith(".trashed-") && !PathGuard.isPart(k.name)) files[k.rel] = k.info.bytes
        }
        Scanner.noteListed(files)
        return Reply.Json(JSONObject().put("ok", true).put("path", n.rel).put("entries", arr).put("truncated", truncated))
    }

    private fun stat(ctx: Context, cmd: JSONObject): Reply {
        val rel = path(ctx, cmd).let { if (it is Reply) return it else it as String }
        val n = node(ctx, rel).let { if (it is Reply) return it else it as FsAccess.Node }
        val i = FsAccess.info(ctx, n) ?: return Reply.error("not_found")
        val o = JSONObject().put("ok", true).put("path", n.rel).put("dir", i.dir).put("bytes", i.bytes)
            .put("mtime", i.mtime).put("kind", if (i.dir) "dir" else Scanner.kind(PathGuard.name(n.rel)))
        if (!i.dir && i.mime != null) o.put("mime", i.mime)
        if (cmd.optBoolean("hash") && !i.dir) {
            if (i.bytes > MAX_HASH_BYTES) o.put("sha256_skipped", "too_big")
            else FsAccess.hash(ctx, n)?.let { o.put("sha256", it) }
        }
        return Reply.Json(o)
    }

    private fun get(ctx: Context, cmd: JSONObject): Reply {
        val rel = path(ctx, cmd).let { if (it is Reply) return it else it as String }
        val offset = cmd.optLong("offset", 0L)
        val length = cmd.optInt("length", MAX_CHUNK)
        if (offset < 0 || length < 1 || length > MAX_CHUNK) return Reply.error("bad_args", "offset >= 0, 1 <= length <= $MAX_CHUNK")
        val n = node(ctx, rel).let { if (it is Reply) return it else it as FsAccess.Node }
        val i = FsAccess.info(ctx, n) ?: return Reply.error("not_found")
        if (i.dir) return Reply.error("bad_args", "is a folder")
        if (offset > i.bytes) return Reply.error("bad_offset", "past the end")
        val bytes = FsAccess.read(ctx, n, offset, length) ?: return Reply.error("denied", "cannot read")
        val h = JSONObject().put("path", n.rel).put("offset", offset).put("size", bytes.size).put("total", i.bytes)
            .put("eof", offset + bytes.size >= i.bytes).put("mime", i.mime ?: "application/octet-stream")
        return Reply.Binary(h, bytes)
    }

    private fun put(ctx: Context, cmd: JSONObject): Reply {
        val rel = path(ctx, cmd).let { if (it is Reply) return it else it as String }
        if (PathGuard.isPart(rel)) return Reply.error("bad_args", "reserved suffix")
        val offset = cmd.optLong("offset", -1L)
        if (offset < 0) return Reply.error("bad_args", "offset missing")
        val b64 = cmd.optString("data_b64", "")
        if (b64.length > (MAX_CHUNK / 3 + 1) * 4 + 4) return Reply.error("too_big", "at most $MAX_CHUNK bytes per fs.put")
        val data = try { Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { return Reply.error("bad_args", "data_b64") }
        if (data.size > MAX_CHUNK) return Reply.error("too_big", "at most $MAX_CHUNK bytes per fs.put")
        val w = FsAccess.putPart(ctx, rel, offset, data)
        if (!w.ok) {
            val o = JSONObject().put("error", w.error)
            if (!w.detail.isNullOrEmpty()) o.put("detail", w.detail)
            if (w.error == "bad_offset") o.put("next_offset", w.value)
            return Reply.Json(o)
        }
        return Reply.Json(JSONObject().put("ok", true).put("next_offset", w.value))
    }

    private fun commit(ctx: Context, cmd: JSONObject): Reply {
        val rel = path(ctx, cmd).let { if (it is Reply) return it else it as String }
        if (PathGuard.isPart(rel)) return Reply.error("bad_args", "reserved suffix")
        val sha = cmd.optString("sha256", "")
        if (!Regex("[0-9a-fA-F]{64}").matches(sha)) return Reply.error("bad_args", "sha256 must be 64 hex chars")
        val w = FsAccess.commit(ctx, rel, sha)
        if (!w.ok) return Reply.error(w.error ?: "internal", w.detail)
        return Reply.Json(JSONObject().put("ok", true).put("bytes", w.value))
    }

    private fun mkdir(ctx: Context, cmd: JSONObject): Reply {
        val rel = path(ctx, cmd).let { if (it is Reply) return it else it as String }
        if (PathGuard.isPart(rel)) return Reply.error("bad_args", "reserved suffix")
        val w = FsAccess.mkdir(ctx, rel)
        if (!w.ok) return Reply.error(w.error ?: "internal", w.detail)
        return Reply.Json(JSONObject().put("ok", true).put("existed", w.value == 1L))
    }

    private fun propose(ctx: Context, cmd: JSONObject): Reply {
        val arr = cmd.optJSONArray("paths") ?: return Reply.error("bad_args", "paths missing")
        if (arr.length() > DeleteGate.MAX_PATHS) return Reply.error("bad_args", "at most ${DeleteGate.MAX_PATHS} paths per proposal")
        val ok = ArrayList<String>(); val rejected = JSONArray()
        for (i in 0 until arr.length()) {
            val raw = arr.opt(i) as? String
            when (val r = PathGuard.check(raw, ctx.packageName)) {
                is PathGuard.Result.Ok -> if (PathGuard.isPart(r.rel) || PathGuard.name(r.rel).startsWith(".trashed-")) rejected.put(raw) else ok.add(r.rel)
                is PathGuard.Result.Bad -> rejected.put(raw ?: JSONObject.NULL)
            }
        }
        val out = Proposals.create(ctx, ok, cmd.optString("reason", ""), cmd.optBoolean("preselect", false))
        if (rejected.length() > 0) {
            val all = out.optJSONArray("rejected") ?: JSONArray()
            for (i in 0 until rejected.length()) all.put(rejected.get(i))
            out.put("rejected", all)
        }
        return withProposal(Reply.Json(out))
    }
}
