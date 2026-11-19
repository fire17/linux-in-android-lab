package com.fire17.linuxdesk.storage

/**
 * The fs.* path guard, as pure Kotlin (unit-tested on the JVM). Every genie-supplied path passes here
 * BEFORE it touches a File or a document Uri; FsAccess then re-checks the canonical path on the device
 * (symlinks), so a link can't lead out of shared storage or into Android/data.
 *
 * Paths are relative to /storage/emulated/0 (spec 01 §1.3), '/'-separated. Refused: absolute paths, `..`,
 * NUL and other control characters, backslashes, over-long names, and anything under Android/data or
 * Android/obb, except the app's own `Android/data/<package>/files` (its app-specific dir, spec 02 #12).
 * Shared storage is case-insensitive, so the Android/data check is too.
 */
object PathGuard {
    const val PART_SUFFIX = ".linuxdesk-part"
    const val MAX_SEGMENT = 255
    const val MAX_PATH = 1024

    sealed class Result {
        data class Ok(val rel: String) : Result()
        data class Bad(val error: String, val detail: String) : Result()
    }

    /** The app's own external files dir, relative to shared storage. */
    fun appDir(pkg: String) = "Android/data/$pkg/files"

    /**
     * Normalize [raw]. `""` (the storage root) is allowed only with [allowRoot]. [pkg] = this app's package,
     * whose own files dir is exempt from the Android/data ban.
     */
    fun check(raw: String?, pkg: String, allowRoot: Boolean = false): Result {
        val s = raw ?: return Result.Bad("bad_args", "path missing")
        if (s.length > MAX_PATH) return Result.Bad("bad_args", "path too long")
        if (s.startsWith("/")) return Result.Bad("bad_args", "absolute path")
        for (c in s) {
            if (c == '\u0000' || c < ' ' || c == '\u007f') return Result.Bad("bad_args", "control character")
            if (c == '\\') return Result.Bad("bad_args", "backslash")
        }
        val segs = ArrayList<String>()
        for (seg in s.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return Result.Bad("bad_args", "parent segment")
            if (seg.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT) return Result.Bad("bad_args", "name too long")
            segs.add(seg)
        }
        if (segs.isEmpty()) return if (allowRoot) Result.Ok("") else Result.Bad("bad_args", "path is the storage root")
        if (forbidden(segs, pkg)) return Result.Bad("denied", "Android/data and Android/obb are off limits")
        // Own app dir: rewrite the first four segments to their canonical case.
        if (segs.size >= 4 && isOwnAppDir(segs, pkg)) { segs[0] = "Android"; segs[1] = "data"; segs[2] = pkg; segs[3] = "files" }
        return Result.Ok(segs.joinToString("/"))
    }

    private fun isOwnAppDir(segs: List<String>, pkg: String) =
        segs.size >= 4 && segs[0].equals("Android", true) && segs[1].equals("data", true) &&
            segs[2].equals(pkg, true) && segs[3].equals("files", true)

    /** True when [segs] fall under Android/data or Android/obb (case-insensitive) and not under the own files dir. */
    fun forbidden(segs: List<String>, pkg: String): Boolean {
        if (segs.size < 2 || !segs[0].equals("Android", true)) return false
        val area = segs[1]
        if (!area.equals("data", true) && !area.equals("obb", true)) return false
        return !(area.equals("data", true) && isOwnAppDir(segs, pkg))
    }

    /** The same test for an already-normalized relative path (used after canonicalization on the device). */
    fun forbiddenRel(rel: String, pkg: String) = forbidden(rel.split('/').filter { it.isNotEmpty() }, pkg)

    /** [rel] is [root] itself or inside it. Case-insensitive, like the storage it describes. */
    fun within(rel: String, root: String): Boolean =
        root.isEmpty() || rel.equals(root, true) || rel.lowercase().startsWith(root.lowercase() + "/")

    fun isPart(rel: String) = rel.endsWith(PART_SUFFIX, ignoreCase = true)

    fun parent(rel: String) = rel.substringBeforeLast('/', "")
    fun name(rel: String) = rel.substringAfterLast('/')
    fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
}
