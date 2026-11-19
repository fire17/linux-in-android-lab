package com.fire17.linuxdesk.hw

import java.io.File

/**
 * Performance snapshot (ld-perf lane): which of OUR processes use the CPU right now. Read-only, pure JVM (no android
 * imports, so PerfStatTest runs on the plain JVM).
 *
 * Android mounts /proc with hidepid, so the app sees only its own uid: the app itself, the :x11 X server, proot and
 * every guest process (Chromium, i3, PulseAudio), which is exactly the set that competes with the sound pump.
 * CPU % = (utime+stime ticks delta) / (CLK_TCK × seconds) × 100, so 100 % = one full core (the phone has 8).
 */
object PerfStat {
    const val CLK_TCK = 100   // Linux USER_HZ on arm64 Android (getconf CLK_TCK = 100 on Tami's phone)

    data class Proc(val pid: Int, val name: String, val ticks: Long, val rssPages: Long)
    data class Use(val pid: Int, val name: String, val cpuPercent: Double, val rssMb: Long)

    /** Parses one /proc/<pid>/stat line. The name is inside (...) and may contain spaces or ')'. Null if malformed. */
    fun parseStat(line: String): Proc? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val name = line.substring(open + 1, close)
        // After ") ": field 3 (state) is index 0, so utime (14) = 11, stime (15) = 12, rss (24) = 21.
        val f = line.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < 22) return null
        val ut = f[11].toLongOrNull() ?: return null
        val st = f[12].toLongOrNull() ?: return null
        return Proc(pid, name, ut + st, f[21].toLongOrNull() ?: 0L)
    }

    /** Every readable /proc/<pid>/stat under [procDir]. Unreadable or vanished pids are skipped. */
    fun read(procDir: File = File("/proc")): Map<Int, Proc> {
        val out = HashMap<Int, Proc>()
        procDir.listFiles()?.forEach { d ->
            if (d.name.toIntOrNull() == null) return@forEach
            val p = try { parseStat(File(d, "stat").readText()) } catch (_: Exception) { null } ?: return@forEach
            out[p.pid] = p
        }
        return out
    }

    /** CPU use between two reads [ms] apart, busiest first. Processes that started in between are left out. */
    fun diff(a: Map<Int, Proc>, b: Map<Int, Proc>, ms: Long, pageKb: Long = 4): List<Use> {
        if (ms <= 0) return emptyList()
        return b.values.mapNotNull { p ->
            val before = a[p.pid] ?: return@mapNotNull null
            val d = (p.ticks - before.ticks).coerceAtLeast(0)
            Use(p.pid, p.name, d * 100.0 * 1000.0 / (CLK_TCK * ms), p.rssPages * pageKb / 1024)
        }.sortedWith(compareByDescending<Use> { it.cpuPercent }.thenByDescending { it.rssMb })
    }

    /** Blocking (≈[ms]): two reads and their diff. */
    fun sample(ms: Long = 1000, procDir: File = File("/proc")): List<Use> {
        val a = read(procDir)
        val t0 = System.nanoTime()
        Thread.sleep(ms)
        val b = read(procDir)
        return diff(a, b, (System.nanoTime() - t0) / 1_000_000)
    }

    /** "MemAvailable" from /proc/meminfo in MB, or -1. */
    fun memAvailableMb(meminfo: String): Long {
        val line = meminfo.lineSequence().firstOrNull { it.startsWith("MemAvailable:") } ?: return -1
        return line.removePrefix("MemAvailable:").trim().substringBefore(' ').toLongOrNull()?.div(1024) ?: -1
    }

    /** Extra lines for [report] (AudioOut registers its stats line here; kept as a seam so this file stays pure JVM). */
    @Volatile var soundLine: () -> String = { "Sound: no stats" }

    /** Blocking (≈[ms]): CPU top list + MemAvailable + the sound line. For the Hardware screen and sys.perf. */
    fun report(ms: Long = 2_000, procDir: File = File("/proc")): String {
        val uses = sample(ms, procDir)
        val mem = try { memAvailableMb(File(procDir, "meminfo").readText()) } catch (_: Exception) { -1L }
        val sound = try { soundLine() } catch (t: Throwable) { "Sound: " + t.javaClass.simpleName }
        return format(uses) + "\nMemory available: " + (if (mem >= 0) "$mem MB" else "unknown") + "\n" + sound
    }

    /** sys.perf body: {top:[{pid,name,cpu,rss_mb}], cpu_total, mem_available_mb, sound, text}. */
    fun json(ms: Long = 1_000, procDir: File = File("/proc"), top: Int = 12): org.json.JSONObject {
        val uses = sample(ms, procDir)
        val mem = try { memAvailableMb(File(procDir, "meminfo").readText()) } catch (_: Exception) { -1L }
        val arr = org.json.JSONArray()
        uses.take(top).forEach {
            arr.put(org.json.JSONObject().put("pid", it.pid).put("name", it.name)
                .put("cpu", Math.round(it.cpuPercent * 10) / 10.0).put("rss_mb", it.rssMb))
        }
        val sound = try { soundLine() } catch (t: Throwable) { "Sound: " + t.javaClass.simpleName }
        return org.json.JSONObject().put("top", arr).put("cpu_total", Math.round(uses.sumOf { it.cpuPercent } * 10) / 10.0)
            .put("mem_available_mb", mem).put("sound", sound).put("sample_ms", ms)
    }

    /** Readable summary: the [top] busiest processes, one per line. */
    fun format(uses: List<Use>, top: Int = 8): String {
        if (uses.isEmpty()) return "CPU: no readable processes"
        val total = uses.sumOf { it.cpuPercent }
        val sb = StringBuilder("CPU (100%% = 1 core): total %.1f%%\n".format(total))
        uses.take(top).forEach { sb.append("  %5.1f%%  %5d MB  %s (%d)\n".format(it.cpuPercent, it.rssMb, it.name, it.pid)) }
        return sb.toString().trimEnd()
    }
}
