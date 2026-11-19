package com.fire17.linuxdesk.camera

/**
 * Cameras v4: camera lines in the session log, so every `sys.logs` pull and the Share-logs zip carry them
 * (Tami 21:41: "it should be sent to you if there are any problems, so the system can adapt").
 *
 *   cameras: {CameraDiag JSON, one line}                    — at each session start and after the first genie hello
 *   camera-error: <id> <stage> <ExceptionClass>: <message>  — every camera open/stream failure
 *
 * [sink] is SessionBus.log in the app (→ SessionLog.ring → sys.logs, + logcat); tests swap it. The formatters are
 * pure. Messages are single-line and capped, and carry no frame data.
 */
object CameraLog {
    const val MAX_LINE = 6_000

    @Volatile var sink: (String) -> Unit = { line -> com.fire17.linuxdesk.genie.SessionLog.ring.add(line) }

    /** "cameras: {...}" — one line, capped. */
    fun camerasLine(json: String): String = "cameras: " + oneLine(json).take(MAX_LINE - 9)

    /** "camera-error: <id> <stage> <Class>: <msg>" — stage is a single token (open/configure/repeat/session/…). */
    fun errorLine(id: String, stage: String, e: Throwable?, detail: String? = null): String {
        val st = stage.trim().ifEmpty { "unknown" }.replace(Regex("\\s+"), "_")
        val ex = when {
            e != null -> e.javaClass.simpleName + (e.message?.let { ": " + oneLine(it) } ?: "")
            detail != null -> oneLine(detail)
            else -> "-"
        }
        return "camera-error: ${id.ifEmpty { "-" }} $st $ex".take(500)
    }

    fun cameras(json: String) = emit(camerasLine(json))
    fun error(id: String, stage: String, e: Throwable?, detail: String? = null) = emit(errorLine(id, stage, e, detail))

    private fun emit(line: String) { try { sink(line) } catch (_: Throwable) {} }
    private fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ')

    /** Reads the inventory (read-only, no permission) and logs "cameras: {...}" on a background thread. */
    fun logInventory(ctx: android.content.Context, why: String) {
        val app = ctx.applicationContext
        Thread({
            try {
                val j = CameraDiag.json(CameraDiag.read(app)).put("why", why)
                cameras(j.toString())
            } catch (t: Throwable) { error("-", "inventory", t) }
        }, "cam-inventory").apply { isDaemon = true; start() }
    }

    /** Once per process: the first genie hello logs the inventory (session starts log it every time). */
    private val helloLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    fun firstHelloOnce(): Boolean = helloLogged.compareAndSet(false, true)
}
