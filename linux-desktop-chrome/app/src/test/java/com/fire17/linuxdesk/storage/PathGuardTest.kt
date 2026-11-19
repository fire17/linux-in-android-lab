package com.fire17.linuxdesk.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGuardTest {
    private val pkg = "com.fire17.linuxdesk"

    private fun ok(raw: String?, root: Boolean = false): String {
        val r = PathGuard.check(raw, pkg, root)
        assertTrue("expected Ok for '$raw', got $r", r is PathGuard.Result.Ok)
        return (r as PathGuard.Result.Ok).rel
    }

    private fun bad(raw: String?, code: String = "bad_args", root: Boolean = false) {
        val r = PathGuard.check(raw, pkg, root)
        assertTrue("expected Bad for '$raw', got $r", r is PathGuard.Result.Bad)
        assertEquals("code for '$raw'", code, (r as PathGuard.Result.Bad).error)
    }

    @Test fun normalizes() {
        assertEquals("Download/a.pdf", ok("Download/a.pdf"))
        assertEquals("Download/a.pdf", ok("Download//a.pdf"))
        assertEquals("Download/a.pdf", ok("./Download/./a.pdf"))
        assertEquals("Download", ok("Download/"))
        assertEquals("DCIM/Camera/שלום.jpg", ok("DCIM/Camera/שלום.jpg"))
        assertEquals("a b/c d.txt", ok("a b/c d.txt"))
    }

    @Test fun rejectsTraversalAndAbsolute() {
        bad("../etc/passwd"); bad("Download/../../x"); bad("Download/.."); bad("..")
        bad("/storage/emulated/0/Download/a"); bad("/")
    }

    @Test fun rejectsNulControlAndBackslash() {
        bad("Download/a\u0000.pdf"); bad("Download/a\n.pdf"); bad("Download/\u007f"); bad("Download\\a.pdf")
        bad(null)
    }

    @Test fun rejectsOverlong() {
        bad("x".repeat(256))
        bad(("abcdefgh/").repeat(120))
        ok("x".repeat(255))
    }

    @Test fun rootOnlyWhenAllowed() {
        bad(""); bad("./"); bad("//")
        assertEquals("", ok("", root = true))
        assertEquals("", ok("./", root = true))
        bad("/", root = true)                           // absolute, even with root allowed
    }

    @Test fun androidDataAndObbDenied() {
        bad("Android/data/com.whatsapp/x", "denied")
        bad("Android/obb/game/main.obb", "denied")
        bad("android/DATA/com.whatsapp", "denied")      // case-insensitive storage
        bad("Android/data", "denied")
        bad("Android/obb", "denied")
        bad("Android/data/$pkg", "denied")             // only .../files is the app dir
        bad("Android/data/$pkg/cache/x", "denied")
        bad("Android/data/com.fire17.linuxdeskx/files/x", "denied")
        assertEquals("Android/media/com.whatsapp/x.jpg", ok("Android/media/com.whatsapp/x.jpg"))
        assertEquals("Android", ok("Android"))
    }

    @Test fun ownAppDirAllowedAndCanonicalCase() {
        assertEquals("Android/data/$pkg/files", ok("Android/data/$pkg/files"))
        assertEquals("Android/data/$pkg/files/up/a.bin", ok("android/Data/COM.fire17.linuxdesk/FILES/up/a.bin"))
        assertEquals(PathGuard.appDir(pkg), "Android/data/$pkg/files")
    }

    @Test fun forbiddenRelMatchesCheck() {
        assertTrue(PathGuard.forbiddenRel("Android/data/com.x/y", pkg))
        assertFalse(PathGuard.forbiddenRel("Android/data/$pkg/files/y", pkg))
        assertFalse(PathGuard.forbiddenRel("Download/Android/data/x", pkg))
    }

    @Test fun withinIsSegmentWiseAndCaseInsensitive() {
        assertTrue(PathGuard.within("Download/a", "Download"))
        assertTrue(PathGuard.within("download/a", "Download"))
        assertTrue(PathGuard.within("Download", "Download"))
        assertFalse(PathGuard.within("Downloads/a", "Download"))
        assertTrue(PathGuard.within("anything", ""))
    }

    @Test fun partSuffixAndHelpers() {
        assertTrue(PathGuard.isPart("a/b.zip.linuxdesk-part"))
        assertTrue(PathGuard.isPart("a/b.zip.LINUXDESK-PART"))
        assertFalse(PathGuard.isPart("a/b.zip"))
        assertEquals("a/b", PathGuard.parent("a/b/c.txt"))
        assertEquals("", PathGuard.parent("c.txt"))
        assertEquals("c.txt", PathGuard.name("a/b/c.txt"))
        assertEquals("x", PathGuard.join("", "x"))
        assertEquals("a/x", PathGuard.join("a", "x"))
    }
}
