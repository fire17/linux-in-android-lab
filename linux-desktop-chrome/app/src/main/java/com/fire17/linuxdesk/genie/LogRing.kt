package com.fire17.linuxdesk.genie

import org.json.JSONArray
import org.json.JSONObject

/**
 * A bounded in-memory log with a monotonically increasing line counter, so a reader's cursor survives the
 * buffer dropping old lines (`sys.logs` delta mode). Line `seq` numbers start at 0; the cursor is the seq of
 * the NEXT line to be written (= lines ever added this process). Pure JVM.
 *
 * It holds whatever SessionBus logs, which by contract never contains the pairing token, the session key or
 * iroh secrets. Nothing else may be fed into it.
 */
class LogRing(val capacity: Int = 5000) {
    /** Changes when the process restarts, so a cursor from an older process is recognised and reset. */
    val epoch: Long = System.currentTimeMillis()
    private val buf = ArrayDeque<String>()
    private var next = 0L

    @Synchronized
    fun add(line: String) {
        buf.addLast(if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line)
        next++
        while (buf.size > capacity) buf.removeFirst()
    }

    @Synchronized
    fun cursor(): Long = next

    /** The newest [limit] lines, oldest first (SessionBus.logText). */
    @Synchronized
    fun lastLines(limit: Int): List<String> {
        val k = limit.coerceIn(0, buf.size)
        return List(k) { buf[buf.size - k + it] }
    }

    /** A consistent copy: seq of the first held line, and the lines. */
    @Synchronized
    fun snapshot(): Pair<Long, List<String>> = (next - buf.size) to buf.toList()

    companion object {
        const val MAX_LINE_CHARS = 2000   // 5000 lines x 2000 chars bounds the ring (~10 MB worst case)
    }
}

/** The process-wide session log; SessionBus writes it, the Router's `sys.logs` reads it. */
object SessionLog {
    val ring = LogRing()
}

/**
 * `sys.logs` (read-only, ungated like `sys.audit`): the session log on demand.
 *   mode "tail" (default): the last [n] lines (default 200, max 5000)
 *   mode "all": every line still held
 *   mode "delta": the lines after cursor `since` (+ `epoch`); lines that fell out of the buffer are `dropped`
 * Plus `audit`: the newest genie-audit.jsonl rows (verb/ok/ms/via only; never arguments).
 * Reply: {ok, mode, lines, cursor, epoch, first, dropped, truncated, reset, audit}. Kept under [budget] bytes
 * by cutting the OLDEST lines first (counted in `truncated`).
 */
object SysLogs {
    const val DEFAULT_N = 200
    const val MAX_N = 5000
    const val MAX_AUDIT = 1000
    /** Room left for the frame header and the JSON envelope under Frame.MAX_OUT (8 MiB). */
    const val BUDGET_BYTES = 6 shl 20

    fun answer(cmd: JSONObject, ring: LogRing?, audit: Audit?, budget: Int = BUDGET_BYTES): Reply {
        val mode = cmd.optString("mode", "tail").ifEmpty { "tail" }
        if (mode !in setOf("tail", "all", "delta")) return Reply.error("bad_args", "mode must be tail, all or delta")
        val n = intArg(cmd, "n", DEFAULT_N) ?: return Reply.error("bad_args", "n must be an integer")
        val since = intArg(cmd, "since", 0) ?: return Reply.error("bad_args", "since must be an integer")
        val wantN = n.coerceIn(1, MAX_N)

        val (first, held) = ring?.snapshot() ?: (0L to emptyList())
        val cursor = first + held.size
        val epoch = ring?.epoch ?: 0L
        var dropped = 0L
        var reset = false
        var from = when (mode) {
            "all" -> first
            "tail" -> maxOf(first, cursor - wantN)
            else -> {
                val sameProcess = !cmd.has("epoch") || cmd.optLong("epoch") == epoch
                if (!sameProcess || since > cursor || since < 0) { reset = true; first }
                else if (since < first) { dropped = first - since; first }
                else since.toLong()
            }
        }
        var lines = held.subList((from - first).toInt(), held.size)

        // Audit rows: tail = the newest min(n, 1000); all = the newest 1000; delta = rows with t > audit_since.
        val auditRows = JSONArray()
        if (audit != null) {
            val q = when (mode) {
                "delta" -> audit.query(cmd.optLong("audit_since", 0L) + 1, MAX_AUDIT)
                "all" -> audit.query(0, MAX_AUDIT)
                else -> audit.query(0, minOf(wantN, MAX_AUDIT))
            }
            val rows = q.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) auditRows.put(rows.get(i))
        }
        var auditSince = cmd.optLong("audit_since", 0L)
        for (i in 0 until auditRows.length()) auditSince = maxOf(auditSince, auditRows.getJSONObject(i).optLong("t"))

        // Size cap: drop audit rows beyond what fits, then cut the oldest session lines first.
        var used = auditRows.toString().toByteArray(Charsets.UTF_8).size
        while (used > budget / 4 && auditRows.length() > 0) {
            used -= auditRows.get(0).toString().toByteArray(Charsets.UTF_8).size + 1
            auditRows.remove(0)
        }
        var bytes = 0L
        var keepFrom = lines.size
        while (keepFrom > 0) {
            val b = lines[keepFrom - 1].toByteArray(Charsets.UTF_8).size + 8   // quotes, comma, escapes slack
            if (used + bytes + b > budget) break
            bytes += b
            keepFrom--
        }
        val truncated = keepFrom
        if (keepFrom > 0) { lines = lines.subList(keepFrom, lines.size); from += keepFrom }

        return Reply.Json(JSONObject().put("ok", true).put("mode", mode)
            .put("lines", JSONArray(lines)).put("first", from).put("cursor", cursor).put("epoch", epoch)
            .put("dropped", dropped).put("truncated", truncated).put("reset", reset)
            .put("audit", auditRows).put("audit_since", auditSince))
    }

    /** Absent -> [default]; an integral number -> it; anything else -> null (bad_args). */
    private fun intArg(cmd: JSONObject, key: String, default: Int): Int? {
        if (!cmd.has(key) || cmd.isNull(key)) return default
        val v = cmd.opt(key)
        if (v !is Number) return null
        val d = v.toDouble()
        if (d.isNaN() || d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return null
        return v.toInt()
    }
}
