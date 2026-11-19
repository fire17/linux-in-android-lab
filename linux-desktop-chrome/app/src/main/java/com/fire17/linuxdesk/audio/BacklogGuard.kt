package com.fire17.linuxdesk.audio

/**
 * Decides when AudioOut may drop queued sound (ld-perf lane). Pure logic, no android imports (BacklogGuardTest).
 *
 * Why: the guest's null sink renders AHEAD of real time, up to its configured latency (Tami's phone, `pactl list
 * sinks` 21:39: "Latency: 40–100 ms, configured 300000 usec"), so the TCP stream arrives in bursts. The old rule
 * dropped whenever the INSTANT backlog was over 200 ms, down to 40 ms: one CPU hiccup of the pump (or one burst)
 * threw away ~160 ms of sound, an audible jump. Not yet measured on the phone how often this fired (AudioOut now
 * counts it: "sound: stats …" lines and the Performance section).
 *
 * Real clock drift (null-sink clock vs the phone's DAC) is slow and shows as a backlog that NEVER drains. So drop
 * only when the SMALLEST backlog seen over a whole window is still over [maxBytes], and then only down to
 * [targetBytes]. Bursts make the maximum high but the minimum stays low, so they are never cut.
 */
class BacklogGuard(
    private val maxBytes: Int,
    private val targetBytes: Int,
    private val frameBytes: Int,
    private val windowMs: Long = 2_000,
) {
    private var windowStart = -1L
    private var minSeen = Int.MAX_VALUE

    /** Largest instant backlog since the last [takeMaxBurst] (diagnostics: burst size). */
    var maxSeen = 0
        private set

    /** Bytes to skip now (frame-aligned, 0 = keep everything). [backlog] = bytes queued in the socket. */
    fun feed(backlog: Int, nowMs: Long): Int {
        if (backlog > maxSeen) maxSeen = backlog
        if (windowStart < 0) windowStart = nowMs
        if (backlog < minSeen) minSeen = backlog
        if (nowMs - windowStart < windowMs) return 0
        val floor = minSeen
        windowStart = nowMs
        minSeen = Int.MAX_VALUE
        if (floor <= maxBytes) return 0
        // Drop only what is ALWAYS queued (the floor), never more than is queued right now.
        val drop = minOf(floor - targetBytes, backlog)
        return if (drop <= 0) 0 else drop / frameBytes * frameBytes
    }

    fun takeMaxBurst(): Int = maxSeen.also { maxSeen = 0 }

    fun reset() { windowStart = -1L; minSeen = Int.MAX_VALUE; maxSeen = 0 }
}
