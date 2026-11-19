package com.fire17.linuxdesk.storage

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue

/**
 * Port of Storage Genie's Scanner.kt (fs.scan). The reply shape is SG's `scan` reply, unchanged, plus two
 * additive fields: `roots` (what was walked: [""] = all of shared storage, else the app dir + SAF folders)
 * and `fs_mode`. Only sizes + relative paths ever leave the phone, never contents.
 *
 * Differences from SG, both forced by LinuxDesk's storage model (spec 01 §1.3, 02 #12 + §03):
 *  - the walk goes through [FsAccess], so in scoped mode it covers only the app dir + the picked folders;
 *  - "seen" (what fs.propose_delete may name) = the latest scan's files PLUS files returned by fs.list since
 *    that scan (spec: "each must be from the latest fs.scan or fs.list").
 */
object Scanner {
    private const val MAX_FILES = 400_000
    private const val MAX_LISTED = 50_000

    /** Relative path -> bytes, from the latest scan. */
    @Volatile var lastPaths: Map<String, Long> = emptyMap()
        private set
    @Volatile var lastScanAt = 0L
        private set
    private val listed = LinkedHashMap<String, Long>()

    /** fs.list reports the files it returned; they become proposable too (bounded, oldest dropped). */
    fun noteListed(entries: Map<String, Long>) = synchronized(listed) {
        for ((k, v) in entries) { listed.remove(k); listed[k] = v }
        val it = listed.entries.iterator()
        while (listed.size > MAX_LISTED && it.hasNext()) { it.next(); it.remove() }
    }

    /** The size the genie was shown for [rel] (latest scan, else a later fs.list), or null if never shown. */
    fun seen(rel: String): Long? = synchronized(listed) { listed[rel] } ?: lastPaths[rel]

    fun hasSeen(): Boolean = lastPaths.isNotEmpty() || synchronized(listed) { listed.isNotEmpty() }

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "wmv", "flv")
    private val IMAGE = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "raw", "bmp")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2")
    private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "odt")

    fun kind(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in VIDEO -> "video"; in IMAGE -> "image"; in AUDIO -> "audio"
            "apk", "apks", "xapk" -> "apk"; in ARCHIVE -> "archive"; in DOC -> "document"
            else -> "other"
        }
    }

    private class F(val rel: String, val bytes: Long, val mtime: Long, val kind: String)

    fun scan(ctx: Context, top: Int = 40): JSONObject {
        val t0 = System.currentTimeMillis()
        val topN = top.coerceIn(1, 500)
        val topQ = PriorityQueue<F>(compareBy { it.bytes })
        val byType = LinkedHashMap<String, Long>()
        listOf("video", "image", "audio", "apk", "archive", "document", "other").forEach { byType[it] = 0L }
        val folders = HashMap<String, LongArray>() // path -> [bytes, files], depth ≤ 3
        val dupKey = HashMap<String, MutableList<String>>()
        val dupBytes = HashMap<String, Long>()
        val videos = ArrayList<F>()
        val wa = LinkedHashMap<String, Long>().apply { listOf("bytes", "video", "image", "audio", "documents").forEach { put(it, 0L) } }
        val paths = HashMap<String, Long>()
        // SG v0.5.2: MediaStore Trash renames files to ".trashed-<expiry>-<name>". Kept out of every bucket
        // (and out of propose_delete) and reported on their own as `trash`.
        var trashBytes = 0L; var trashFiles = 0
        var count = 0
        var truncated = false
        val roots = FsAccess.roots(ctx)

        for (r in roots) {
            if (truncated) break
            FsAccess.walk(ctx, r) { rel, name, n, m ->
                if (++count > MAX_FILES) { truncated = true; return@walk false }
                if (paths.containsKey(rel)) return@walk true   // overlapping picked folders
                if (name.startsWith(".trashed-")) { trashBytes += n; trashFiles++; return@walk true }
                if (PathGuard.isPart(name)) return@walk true      // unfinished fs.put uploads
                val k = kind(name)
                paths[rel] = n
                byType[k] = (byType[k] ?: 0L) + n
                val parts = rel.split('/')
                for (d in 1..minOf(3, parts.size - 1)) {
                    val key = parts.subList(0, d).joinToString("/")
                    val a = folders.getOrPut(key) { LongArray(2) }; a[0] += n; a[1]++
                }
                val ff = F(rel, n, m, k)
                topQ.add(ff); if (topQ.size > topN) topQ.poll()
                val low = rel.lowercase()
                if (k == "video" && (low.startsWith("download/") || low.startsWith("movies/") ||
                        low.contains("telegram") || low.contains("whatsapp video"))) videos.add(ff)
                if (low.contains("whatsapp/media/") || low.startsWith("whatsapp/")) {
                    wa["bytes"] = wa["bytes"]!! + n
                    val wk = when (k) { "video" -> "video"; "image" -> "image"; "audio" -> "audio"; else -> "documents" }
                    wa[wk] = wa[wk]!! + n
                }
                if (n >= 1_000_000) {
                    val key = "$name|$n"
                    dupKey.getOrPut(key) { ArrayList() }.add(rel); dupBytes[key] = n
                }
                true
            }
        }

        lastPaths = paths
        lastScanAt = System.currentTimeMillis()
        synchronized(listed) { listed.clear() }

        val (total, free) = try { StatFs(FsAccess.root.path).let { it.totalBytes to it.availableBytes } } catch (_: Throwable) { -1L to -1L }
        val out = JSONObject().put("ok", true).put("t", lastScanAt / 1000)
            .put("storage", JSONObject().put("total", total).put("free", free).put("used", if (total >= 0) total - free else -1L))
        out.put("by_type", JSONObject(byType as Map<*, *>))
        out.put("folders", JSONArray().also { arr ->
            folders.entries.sortedByDescending { it.value[0] }.take(25).forEach {
                arr.put(JSONObject().put("path", it.key).put("bytes", it.value[0]).put("files", it.value[1]))
            }
        })
        out.put("top_files", JSONArray().also { arr ->
            topQ.sortedByDescending { it.bytes }.forEach { arr.put(fj(it).put("kind", it.kind)) }
        })
        out.put("downloaded_videos", JSONArray().also { arr ->
            videos.sortedByDescending { it.bytes }.take(100).forEach { arr.put(fj(it)) }
        })
        out.put("whatsapp", JSONObject(wa as Map<*, *>))
        out.put("duplicates", JSONArray().also { arr ->
            dupKey.filter { it.value.size > 1 }.entries
                .sortedByDescending { dupBytes[it.key]!! * (it.value.size - 1) }.take(40).forEach {
                    arr.put(JSONObject().put("bytes", dupBytes[it.key]).put("name", it.key.substringBefore('|'))
                        .put("paths", JSONArray(it.value)))
                }
        })
        out.put("trash", JSONObject().put("bytes", trashBytes).put("files", trashFiles))
        out.put("files_scanned", minOf(count, MAX_FILES)).put("truncated", truncated)
        out.put("roots", JSONArray(roots)).put("fs_mode", if (StoragePrefs.allFilesMode(ctx)) "all_files" else "scoped")
        out.put("took_ms", System.currentTimeMillis() - t0)
        return out
    }

    private fun fj(f: F) = JSONObject().put("path", f.rel).put("bytes", f.bytes).put("mtime", f.mtime)
}
