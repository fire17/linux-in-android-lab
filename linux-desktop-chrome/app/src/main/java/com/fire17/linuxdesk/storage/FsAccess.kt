package com.fire17.linuxdesk.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where fs.* may reach, and the file operations behind every verb. Two kinds of node:
 *  - [Node.F]: a real File. Used for the app's own external files dir (always), and for all of shared storage
 *    while [StoragePrefs.allFilesMode] holds.
 *  - [Node.D]: a SAF document inside a folder the person picked (scoped mode). Its document id is
 *    "primary:<rel>", so relative paths map 1:1 onto the system's external storage provider.
 * Paths arrive here already normalized by [PathGuard]. Everything here re-checks containment after
 * canonicalization (symlinks), and never follows a link out of shared storage or into Android/data|obb.
 * Nothing here reads or logs file contents except [read] and [hash], whose bytes go only to the caller.
 */
object FsAccess {
    sealed class Node {
        abstract val rel: String
        class F(override val rel: String, val file: File) : Node()
        class D(override val rel: String, val tree: Uri, val doc: Uri) : Node()
    }

    sealed class Loc {
        class Ok(val node: Node) : Loc()
        class Err(val error: String, val detail: String) : Loc()
    }

    class Info(val dir: Boolean, val bytes: Long, val mtime: Long, val mime: String?)

    val root: File get() = Environment.getExternalStorageDirectory()

    fun appRel(ctx: Context) = PathGuard.appDir(ctx.packageName)
    private fun appDir(ctx: Context): File? = try { ctx.getExternalFilesDir(null) } catch (_: Throwable) { null }

    /** The roots fs.* may use when NOT in all-files mode: the app's own dir + SAF folders (relative paths). */
    fun scopedRoots(ctx: Context): List<String> = listOf(appRel(ctx)) + StoragePrefs.folders(ctx).map { it.first }

    /** The roots fs.scan walks right now. "" = all of shared storage. */
    fun roots(ctx: Context): List<String> = if (StoragePrefs.allFilesMode(ctx)) listOf("") else scopedRoots(ctx)

    // ------------------------------------------------------------ locate

    fun locate(ctx: Context, rel: String): Loc {
        val pkg = ctx.packageName
        val app = appRel(ctx)
        if (PathGuard.within(rel, app)) {
            val base = appDir(ctx) ?: return Loc.Err("not_ready", "app storage is not mounted")
            val rest = if (rel.length > app.length) rel.substring(app.length + 1) else ""
            return contained(base, rest, app) ?: Loc.Err("denied", "path leaves the app folder")
        }
        if (PathGuard.forbiddenRel(rel, pkg)) return Loc.Err("denied", "Android/data and Android/obb are off limits")
        if (StoragePrefs.allFilesMode(ctx)) {
            val loc = contained(root, rel, "") ?: return Loc.Err("denied", "path leaves shared storage")
            val n = (loc as Loc.Ok).node
            if (PathGuard.forbiddenRel(n.rel, pkg)) return Loc.Err("denied", "Android/data and Android/obb are off limits")
            return loc
        }
        for ((folder, tree) in StoragePrefs.folders(ctx)) if (PathGuard.within(rel, folder)) {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId(rel))
            return Loc.Ok(Node.D(rel, tree, doc))
        }
        return Loc.Err("need_permission", if (StoragePrefs.allFilesGranted())
            "All-files access is not used while the XOR guard holds; pick a folder in LinuxDesk"
            else "outside the folders LinuxDesk may use; grant All-files access or pick a folder in LinuxDesk")
    }

    private fun docId(rel: String) = "primary:$rel"

    /** [base]/[rest] after canonicalization, still inside [base]; rel is reported as [prefix]/<canonical rest>. */
    private fun contained(base: File, rest: String, prefix: String): Loc? {
        val b = try { base.canonicalFile } catch (_: Throwable) { return null }
        val f = try { (if (rest.isEmpty()) b else File(b, rest)).canonicalFile } catch (_: Throwable) { return null }
        val inside = f.path == b.path || f.path.startsWith(b.path + "/")
        if (!inside) return null
        val r = f.path.removePrefix(b.path).trimStart('/')
        return Loc.Ok(Node.F(PathGuard.join(prefix, r).trimEnd('/'), f))
    }

    // ------------------------------------------------------------ read side

    fun info(ctx: Context, n: Node): Info? = when (n) {
        is Node.F -> if (!n.file.exists()) null else Info(n.file.isDirectory, if (n.file.isFile) n.file.length() else 0L,
            n.file.lastModified() / 1000, if (n.file.isFile) mimeOf(n.file.name) else null)
        is Node.D -> try {
            ctx.contentResolver.query(n.doc, arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else {
                    val mime = c.getString(0)
                    val dir = mime == Document.MIME_TYPE_DIR
                    Info(dir, if (dir || c.isNull(1)) 0L else c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2) / 1000,
                        if (dir) null else mime)
                }
            }
        } catch (_: Throwable) { null }
    }

    class Child(val name: String, val rel: String, val info: Info)

    /** Up to [limit] children of directory [n]; second = truncated. Symlinks out of the tree are skipped. */
    fun children(ctx: Context, n: Node, limit: Int): Pair<List<Child>, Boolean> {
        val out = ArrayList<Child>(); var truncated = false
        when (n) {
            is Node.F -> {
                val kids = try { n.file.listFiles() } catch (_: Throwable) { null } ?: return out to false
                for (k in kids.sortedBy { it.name.lowercase() }) {
                    val rel = PathGuard.join(n.rel, k.name)
                    if (k.isDirectory && skipDir(ctx, rel, k)) continue
                    if (out.size >= limit) { truncated = true; break }
                    out.add(Child(k.name, rel, Info(k.isDirectory, if (k.isFile) k.length() else 0L, k.lastModified() / 1000,
                        if (k.isFile) mimeOf(k.name) else null)))
                }
            }
            is Node.D -> {
                val parentId = try { DocumentsContract.getDocumentId(n.doc) } catch (_: Throwable) { return out to false }
                val q = DocumentsContract.buildChildDocumentsUriUsingTree(n.tree, parentId)
                try {
                    ctx.contentResolver.query(q, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                        val rows = ArrayList<Child>()
                        while (c.moveToNext()) {
                            val name = c.getString(0) ?: continue
                            if (name.contains('/') || name == "." || name == "..") continue
                            val dir = c.getString(1) == Document.MIME_TYPE_DIR
                            val rel = PathGuard.join(n.rel, name)
                            if (PathGuard.forbiddenRel(rel, ctx.packageName)) continue
                            rows.add(Child(name, rel, Info(dir, if (dir || c.isNull(2)) 0L else c.getLong(2),
                                if (c.isNull(3)) 0L else c.getLong(3) / 1000, if (dir) null else c.getString(1))))
                        }
                        for (r in rows.sortedBy { it.name.lowercase() }) {
                            if (out.size >= limit) { truncated = true; break }
                            out.add(r)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return out to truncated
    }

    /** Directories never entered: Android/data|obb (except the own app dir) and symlinks. */
    private fun skipDir(ctx: Context, rel: String, f: File): Boolean {
        if (PathGuard.forbiddenRel(rel, ctx.packageName)) return true
        return try { f.canonicalPath != f.absolutePath } catch (_: Throwable) { true }
    }

    fun open(ctx: Context, n: Node): InputStream? = try {
        when (n) {
            is Node.F -> FileInputStream(n.file)
            is Node.D -> ctx.contentResolver.openInputStream(n.doc)
        }
    } catch (_: Throwable) { null }

    /** Up to [length] bytes at [offset]. Null = could not open. */
    fun read(ctx: Context, n: Node, offset: Long, length: Int): ByteArray? {
        val ins = open(ctx, n) ?: return null
        return try {
            ins.use { s ->
                var skip = offset
                while (skip > 0) { val k = s.skip(skip); if (k <= 0) { if (s.read() < 0) return ByteArray(0); skip-- } else skip -= k }
                val buf = ByteArray(length); var got = 0
                while (got < length) { val r = s.read(buf, got, length - got); if (r < 0) break; got += r }
                if (got == length) buf else buf.copyOf(got)
            }
        } catch (_: Throwable) { null }
    }

    fun hash(ctx: Context, n: Node): String? {
        val ins = open(ctx, n) ?: return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            ins.use { s -> val buf = ByteArray(1 shl 16); while (true) { val r = s.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { null }
    }

    /**
     * Visit every regular file under [rootRel] (iterative, no symlinks, no Android/data|obb except the own
     * app dir). [visit] returns false to stop. Hidden ".trashed-" files are visited too; the caller decides.
     */
    fun walk(ctx: Context, rootRel: String, visit: (rel: String, name: String, bytes: Long, mtime: Long) -> Boolean) {
        val start = (locate(ctx, rootRel) as? Loc.Ok)?.node ?: return
        val stack = ArrayDeque<Node>().apply { add(start) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            when (d) {
                is Node.F -> {
                    val kids = try { d.file.listFiles() } catch (_: Throwable) { null } ?: continue
                    for (k in kids) {
                        val rel = PathGuard.join(d.rel, k.name)
                        if (k.isDirectory) { if (!skipDir(ctx, rel, k)) stack.add(Node.F(rel, k)); continue }
                        if (!visit(rel, k.name, k.length(), k.lastModified() / 1000)) return
                    }
                }
                is Node.D -> for (c in children(ctx, d, Int.MAX_VALUE).first) {
                    if (c.info.dir) stack.add(Node.D(c.rel, d.tree, DocumentsContract.buildDocumentUriUsingTree(d.tree, docId(c.rel))))
                    else if (!visit(c.rel, c.name, c.info.bytes, c.info.mtime)) return
                }
            }
        }
    }

    // ------------------------------------------------------------ write side (fs.put / put_commit / mkdir)

    class W(val ok: Boolean, val error: String? = null, val detail: String? = null, val value: Long = 0L)

    private fun bad(e: String, d: String? = null, v: Long = 0L) = W(false, e, d, v)

    /**
     * Append [data] to "<rel>.linuxdesk-part" at [offset]. offset 0 starts (or restarts) the upload; any other
     * offset must equal the part's current size, else bad_offset with next_offset. The target itself must not
     * exist (put_commit would refuse anyway) and its folder must exist.
     */
    fun putPart(ctx: Context, rel: String, offset: Long, data: ByteArray): W {
        val target = (locate(ctx, rel) as? Loc.Ok)?.node ?: return bad("denied", "path not writable")
        if (info(ctx, target) != null) return bad("exists", "path exists; overwriting goes through the delete gate")
        val parent = (locate(ctx, PathGuard.parent(rel)) as? Loc.Ok)?.node
        val pi = parent?.let { info(ctx, it) }
        if (pi == null || !pi.dir) return bad("not_found", "parent folder does not exist")
        val partRel = rel + PathGuard.PART_SUFFIX
        val part = (locate(ctx, partRel) as? Loc.Ok)?.node ?: return bad("denied", "path not writable")
        val have = info(ctx, part)?.bytes ?: -1L
        if (offset != 0L && offset != have) return bad("bad_offset", null, maxOf(have, 0L))
        return try {
            when (part) {
                is Node.F -> {
                    if (offset == 0L) part.file.delete()
                    FileOutputStream(part.file, offset != 0L).use { it.write(data); it.fd.sync() }
                }
                is Node.D -> {
                    var u = part.doc
                    if (offset == 0L || have < 0) {
                        if (have >= 0) DocumentsContract.deleteDocument(ctx.contentResolver, part.doc)
                        val pdoc = (parent as? Node.D)?.doc ?: return bad("internal", "parent is not in the picked folder")
                        val made = DocumentsContract.createDocument(ctx.contentResolver, pdoc,
                            "application/octet-stream", PathGuard.name(partRel)) ?: return bad("internal", "create failed")
                        if (!sameDoc(made, partRel)) {
                            DocumentsContract.deleteDocument(ctx.contentResolver, made)
                            return bad("internal", "provider renamed the part file")
                        }
                        u = made
                    }
                    ctx.contentResolver.openFileDescriptor(u, "rw")?.use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).channel.use { ch -> ch.position(offset); ch.write(java.nio.ByteBuffer.wrap(data)) }
                    } ?: return bad("internal", "open failed")
                }
            }
            W(true, value = offset + data.size)
        } catch (_: Throwable) { bad("internal", "write failed") }
    }

    private fun sameDoc(u: Uri, rel: String): Boolean =
        try { DocumentsContract.getDocumentId(u).equals(docId(rel), ignoreCase = true) } catch (_: Throwable) { false }

    /** Verify the part's sha256, then rename it onto [rel]. NEVER overwrites: an existing [rel] is refused. */
    fun commit(ctx: Context, rel: String, sha256: String): W {
        val target = (locate(ctx, rel) as? Loc.Ok)?.node ?: return bad("denied", "path not writable")
        val part = (locate(ctx, rel + PathGuard.PART_SUFFIX) as? Loc.Ok)?.node ?: return bad("denied", "path not writable")
        if (info(ctx, target) != null) return bad("exists", "path exists; overwriting goes through the delete gate")
        val pinfo = info(ctx, part) ?: return bad("not_found", "no upload in progress for this path")
        val h = hash(ctx, part) ?: return bad("internal", "could not read the upload")
        if (!h.equals(sha256.trim(), ignoreCase = true)) return bad("hash_mismatch", "sha256 differs; upload again from offset 0")
        return try {
            when (part) {
                is Node.F -> {
                    // java.nio move WITHOUT REPLACE_EXISTING refuses an existing target (no overwrite).
                    java.nio.file.Files.move(part.file.toPath(), (target as Node.F).file.toPath())
                }
                is Node.D -> {
                    val moved = DocumentsContract.renameDocument(ctx.contentResolver, part.doc, PathGuard.name(rel))
                        ?: return bad("internal", "rename failed")
                    if (!sameDoc(moved, rel)) {
                        // The provider picked a unique name because the target appeared meanwhile: undo, refuse.
                        DocumentsContract.renameDocument(ctx.contentResolver, moved, PathGuard.name(rel + PathGuard.PART_SUFFIX))
                        return bad("exists", "path appeared during commit")
                    }
                }
            }
            W(true, value = pinfo.bytes)
        } catch (_: java.nio.file.FileAlreadyExistsException) { bad("exists", "path appeared during commit")
        } catch (_: Throwable) { bad("internal", "commit failed") }
    }

    /** Create [rel] and missing parents. An existing folder is ok (value=1); an existing file is `exists`. */
    fun mkdir(ctx: Context, rel: String): W {
        val n = (locate(ctx, rel) as? Loc.Ok)?.node ?: return bad("denied", "path not writable")
        info(ctx, n)?.let { return if (it.dir) W(true, value = 1) else bad("exists", "a file has this name") }
        return when (n) {
            is Node.F -> if (n.file.mkdirs() || n.file.isDirectory) W(true) else bad("internal", "mkdir failed")
            is Node.D -> {
                // Walk down from the deepest existing ancestor inside the picked folder.
                val segs = rel.split('/')
                var i = segs.size - 1
                var parent: Node.D? = null
                while (i > 0) {
                    val pr = segs.subList(0, i).joinToString("/")
                    val p = (locate(ctx, pr) as? Loc.Ok)?.node as? Node.D ?: break
                    val pi = info(ctx, p)
                    if (pi != null) { if (!pi.dir) return bad("exists", "a file is in the way"); parent = p; break }
                    i--
                }
                var cur = parent ?: return bad("need_permission", "outside the picked folder")
                for (j in i until segs.size) {
                    val childRel = segs.subList(0, j + 1).joinToString("/")
                    val made = try { DocumentsContract.createDocument(ctx.contentResolver, cur.doc, Document.MIME_TYPE_DIR, segs[j]) }
                        catch (_: Throwable) { null } ?: return bad("internal", "mkdir failed")
                    if (!sameDoc(made, childRel)) return bad("internal", "provider renamed the folder")
                    cur = Node.D(childRel, cur.tree, made)
                }
                W(true)
            }
        }
    }

    // ------------------------------------------------------------ delete side (ONLY Proposals.complete calls this)

    /** Size now of a deletable regular file at [rel], or null (missing, a folder, or not reachable). */
    fun sizeNow(ctx: Context, rel: String): Long? {
        val n = (locate(ctx, rel) as? Loc.Ok)?.node ?: return null
        val i = info(ctx, n) ?: return null
        return if (i.dir) null else i.bytes
    }

    /** Delete one regular file whose size is still [bytes]. Only reachable from an approved review sheet. */
    internal fun deleteFile(ctx: Context, rel: String, bytes: Long): Boolean {
        val n = (locate(ctx, rel) as? Loc.Ok)?.node ?: return false
        val i = info(ctx, n) ?: return false
        if (i.dir || i.bytes != bytes) return false
        return try {
            when (n) {
                is Node.F -> n.file.delete()
                is Node.D -> DocumentsContract.deleteDocument(ctx.contentResolver, n.doc)
            }
        } catch (_: Throwable) { false }
    }

    /** True when [rel] still exists (used after the system dialog: still there = it did not happen). */
    fun exists(ctx: Context, rel: String): Boolean =
        (locate(ctx, rel) as? Loc.Ok)?.node?.let { info(ctx, it) } != null

    /**
     * rel -> MediaStore content Uri, for files MediaStore indexes as media. Only plain Files in all-files mode
     * outside the app dir (SAF documents and app-private files have no system Trash). Empty below API 30.
     */
    fun mediaUris(ctx: Context, rels: List<String>): Map<String, Uri> {
        if (Build.VERSION.SDK_INT < 30 || rels.isEmpty() || !StoragePrefs.allFilesMode(ctx)) return emptyMap()
        val byAbs = HashMap<String, String>()
        for (r in rels) {
            if (PathGuard.within(r, appRel(ctx))) continue
            val n = (locate(ctx, r) as? Loc.Ok)?.node as? Node.F ?: continue
            byAbs[n.file.absolutePath] = r
        }
        val out = HashMap<String, Uri>()
        val ext = MediaStore.Files.getContentUri("external")
        for (chunk in byAbs.keys.toList().chunked(50)) try {
            val q = "${MediaStore.Files.FileColumns.DATA} IN (${chunk.joinToString(",") { "?" }})"
            ctx.contentResolver.query(ext, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DATA), q, chunk.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) if (c.getInt(1) != MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
                    byAbs[c.getString(2)]?.let { out[it] = ContentUris.withAppendedId(ext, c.getLong(0)) }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return (if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext))
            ?: "application/octet-stream"
    }
}
