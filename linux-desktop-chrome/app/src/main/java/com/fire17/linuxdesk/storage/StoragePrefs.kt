package com.fire17.linuxdesk.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.fire17.linuxdesk.genie.GenieSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-controlled storage switches, stored on the phone only. NONE of them is settable over the genie link,
 * and v1 shows no UI for the first two (spec 01 §1.3, "Delete rule").
 *
 * tapOnlyDelete: Storage Genie parity only. Default false, and in LinuxDesk it changes nothing: every delete
 *   needs the Approve tap on the review sheet (spec 01 §4 rule 6). Kept so a future cycle can't add it silently.
 * autoReturn: after Approve / Cancel / Make changes, open the chat named in `fs.execute.return_url`. Default false.
 *
 * All-files access vs Genie shell (spec 02 #12, as changed by the binding TAMI OVERRIDE, 02 §03): by default
 * both may be on together. The "XOR guard" is the pair lane's setting `GenieSettings.xorGuard` (default OFF).
 *  - guard OFF (default): fs.* uses the whole of shared storage whenever All-files access is granted.
 *  - guard ON and Genie access ON: fs.* is SCOPED (the app's own external files dir + SAF-picked folders), and
 *    [allFilesIntent] refuses (returns null) so the app never asks for the permission in that state.
 *  - no All-files access: always scoped.
 * The honest caveat (a guest process with the same uid may reach shared storage via /proc/<pid>/root and bypass
 * the delete gate) is shown by the pair lane's Pair screen; it is restated in the review screen's footer.
 */
object StoragePrefs {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("ld.storage", Context.MODE_PRIVATE)

    fun tapOnlyDelete(ctx: Context) = p(ctx).getBoolean("tap_only_delete", false)
    fun setTapOnlyDelete(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("tap_only_delete", on).commit() }

    fun autoReturn(ctx: Context) = p(ctx).getBoolean("auto_return", false)
    fun setAutoReturn(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("auto_return", on).commit() }

    /** MANAGE_EXTERNAL_STORAGE is granted (API 30+). Below 30 LinuxDesk never uses legacy storage: false. */
    fun allFilesGranted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** The XOR guard is ON and Genie access is ON: All-files reach must not be used or requested. */
    fun xorHolds(ctx: Context): Boolean = try {
        GenieSettings.xorGuard(ctx) && GenieSettings.genieAccess(ctx)
    } catch (_: Throwable) { false }

    /** fs.* may use the whole of shared storage. */
    fun allFilesMode(ctx: Context): Boolean = allFilesGranted() && !xorHolds(ctx)

    /**
     * The system screen that grants All-files access, or null when it must not be asked for (below API 30, or
     * the XOR guard holds — the caller then explains "turn Genie access off first"). Requested only when the
     * person opens Storage (spec 01 §4 #11).
     */
    fun allFilesIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30 || xorHolds(ctx)) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
    }

    /** For sys.ping / sys.caps (wire lane may embed it): which fs mode is active. No paths of files, only roots. */
    fun capsInfo(ctx: Context): JSONObject = JSONObject()
        .put("fs_mode", if (allFilesMode(ctx)) "all_files" else "scoped")
        .put("all_files_granted", allFilesGranted())
        .put("xor_guard", try { GenieSettings.xorGuard(ctx) } catch (_: Throwable) { false })
        .put("roots", JSONArray(FsAccess.scopedRoots(ctx)))

    // ------------------------------------------------------------ SAF-picked folders (scoped mode)

    private const val EXT_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * The person's picked folders as (relative path under /storage/emulated/0, tree Uri). Only primary shared
     * storage trees from the system's external storage provider count; anything else (SD cards, cloud
     * providers) is ignored in v1, because the protocol's paths are relative to /storage/emulated/0.
     * Trees under Android/data|obb are dropped too. Most specific first.
     */
    fun folders(ctx: Context): List<Pair<String, Uri>> {
        val out = ArrayList<Pair<String, Uri>>()
        val perms = try { ctx.contentResolver.persistedUriPermissions } catch (_: Throwable) { emptyList() }
        for (perm in perms) {
            if (!perm.isReadPermission || !perm.isWritePermission) continue
            val u = perm.uri
            if (u.authority != EXT_AUTHORITY) continue
            val id = try { DocumentsContract.getTreeDocumentId(u) } catch (_: Throwable) { continue }
            if (!id.startsWith("primary:")) continue
            val rel = when (val r = PathGuard.check(id.removePrefix("primary:"), ctx.packageName, allowRoot = true)) {
                is PathGuard.Result.Ok -> r.rel
                is PathGuard.Result.Bad -> continue
            }
            out.add(rel to u)
        }
        return out.sortedByDescending { it.first.length }
    }

    /** Called by ReviewActivity after ACTION_OPEN_DOCUMENT_TREE returned [tree]. True = usable as an fs root. */
    fun addFolder(ctx: Context, tree: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        folders(ctx).any { it.second == tree }
    } catch (_: Throwable) { false }

    fun removeFolder(ctx: Context, tree: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Throwable) {}
    }
}
